/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.objectfile.MachoBindInfoReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoBindInfoSymbol;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;

/**
 * Generates the necessary information, ({@link AppleCxxConditionalLinkInfo}, to be able to
 * determine if linking can be skipped if linked dylibs have changed in a compatible way.
 */
public class AppleMachoConditionalLinkWriteInfo extends AbstractExecutionStep {

  private static final Logger LOG = Logger.get(AppleMachoConditionalLinkWriteInfo.class);

  private final ImmutableMap<SourcePath, SourcePath> sourcePaths;
  private final ImmutableSortedSet<SourcePath> nonParameterInputPaths;
  private final AbsPath argfilePath;
  private final AbsPath filelistPath;
  private final AbsPath infoOutputPath;
  private final RelPath linkedExecutablePath;
  private final AbsPath skipLinkingPath;
  private final ProjectFilesystem filesystem;
  private final SourcePathResolverAdapter sourcePathResolver;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> commandPrefix;
  private final boolean fallback;

  AppleMachoConditionalLinkWriteInfo(
      ProjectFilesystem filesystem,
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableMap<SourcePath, SourcePath> sourceToHashPaths,
      ImmutableSortedSet<SourcePath> nonParameterInputPaths,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath infoOutputPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> commandPrefix,
      boolean fallback) {
    super("apple-conditional-link-write-info");
    Preconditions.checkArgument(commandPrefix.size() > 0);
    this.sourcePaths = sourceToHashPaths;
    this.nonParameterInputPaths = nonParameterInputPaths;
    this.argfilePath = argfilePath;
    this.filelistPath = filelistPath;
    this.infoOutputPath = infoOutputPath;
    this.linkedExecutablePath = linkedExecutablePath;
    this.skipLinkingPath = skipLinkingPath;
    this.filesystem = filesystem;
    this.sourcePathResolver = sourcePathResolver;
    this.environment = environment;
    this.commandPrefix = commandPrefix;
    this.fallback = fallback;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    if (filesystem.exists(skipLinkingPath.getPath())) {
      // The check step is responsible for updating the info
      return StepExecutionResults.SUCCESS;
    }

    Optional<AppleCxxConditionalLinkInfo> maybeRelinkInfo = computeConditionalLinkInfo();
    if (maybeRelinkInfo.isPresent()) {
      // Don't use Optional.ifPresent() as we have to wrap inside try/catch
      writeConditionalLinkInfo(filesystem, infoOutputPath, maybeRelinkInfo.get());
    } else if (fallback) {
      // The output path would have been recorded as a produced artifact, so it needs to exist
      // on the filesystem once the rule finishes execution.
      filesystem.touch(infoOutputPath.getPath());
    }

    return StepExecutionResults.SUCCESS;
  }

  private Optional<AppleCxxConditionalLinkInfo> computeConditionalLinkInfo() throws IOException {
    String argfileHash = filesystem.computeSha1(argfilePath.getPath()).getHash();
    String filelistHash = filesystem.computeSha1(filelistPath.getPath()).getHash();

    ImmutableList.Builder<RelPath> dylibPathsBuilder = ImmutableList.builder();
    ImmutableMap.Builder<String, String> inputPathToHashMapBuilder = ImmutableMap.builder();
    extractInputFileHashesAndDetermineDylibs(dylibPathsBuilder, inputPathToHashMapBuilder);

    Optional<Pair<String, ImmutableSet<MachoBindInfoSymbol>>> maybeUuidAndBindSymbols =
        computeMachoUUIDAndBoundSymbolsOfLinkedExecutable();
    if (!maybeUuidAndBindSymbols.isPresent()) {
      LOG.warn("Could not extract UUID and bound symbols, no relinking info being written");
      return Optional.empty();
    }

    Pair<String, ImmutableSet<MachoBindInfoSymbol>> uuidAndBindSymbols =
        maybeUuidAndBindSymbols.get();

    ImmutableList<String> allBindSymbolNames =
        computeBoundSymbolsToDependentLibraries(uuidAndBindSymbols.getSecond());

    ImmutableList<RelPath> dylibPaths = dylibPathsBuilder.build();
    ImmutableMap<String, ImmutableList<String>> candidateSymbols =
        computeCandidateBoundSymbols(allBindSymbolNames, dylibPaths, filesystem);

    ImmutableList<String> dylibs =
        dylibPaths.stream().map(path -> path.toString()).collect(ImmutableList.toImmutableList());

    return Optional.of(
        AppleCxxConditionalLinkInfo.of(
            uuidAndBindSymbols.getFirst(),
            inputPathToHashMapBuilder.build(),
            argfileHash,
            filelistHash,
            allBindSymbolNames,
            dylibs,
            candidateSymbols,
            environment,
            commandPrefix));
  }

  /**
   * Takes all the bound symbols in the executable (i.e., including system frameworks), passed as
   * {@code boundSymbols}, and returns the list of symbols bound to dylibs. The returned list _will_
   * include symbols from system frameworks (e.g., libSystem, CoreFoundation).
   */
  private ImmutableList<String> computeBoundSymbolsToDependentLibraries(
      ImmutableSet<MachoBindInfoSymbol> boundSymbols) {
    ImmutableList<MachoBindInfoSymbol> allDylibBindInfoSymbols =
        boundSymbols.stream()
            .filter(
                symbol -> {
                  // Flat lookup is deprecated for iOS targets, so only support two-level
                  // namespaces on all platforms (mobile + desktop).
                  MachoBindInfoSymbol.LibraryLookup lookup = symbol.getLibraryLookup();
                  Preconditions.checkState(lookup != MachoBindInfoSymbol.LibraryLookup.FLAT_LOOKUP);
                  return (lookup == MachoBindInfoSymbol.LibraryLookup.DEPENDENT_LIBRARY);
                })
            .collect(ImmutableList.toImmutableList());

    return allDylibBindInfoSymbols.stream()
        .map(symbolInfo -> symbolInfo.getSymbolName())
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * For each input file ({@code sourcePaths}), it reads its SHA1 hash and check if it's a dylib. If
   * it's a dylib, it will add it to the appropriate builders which are passed in.
   */
  private void extractInputFileHashesAndDetermineDylibs(
      ImmutableList.Builder<RelPath> dylibPathsBuilder,
      ImmutableMap.Builder<String, String> inputPathToHashMapBuilder)
      throws IOException {
    for (Map.Entry<SourcePath, SourcePath> fileToHashEntry : sourcePaths.entrySet()) {
      RelPath inputFilePath =
          filesystem.relativize(sourcePathResolver.getAbsolutePath(fileToHashEntry.getKey()));

      boolean isParamInput = !nonParameterInputPaths.contains(fileToHashEntry.getKey());
      if (isParamInput && isLinkableInputFileDylib(filesystem.resolve(inputFilePath))) {
        // We're only interested in recording the dylibs which are inputs to the linker, not
        // unrelated dylibs that might have been part of the inputs to the linker.
        dylibPathsBuilder.add(inputFilePath);
      }

      RelPath hashPath =
          filesystem.relativize(sourcePathResolver.getAbsolutePath(fileToHashEntry.getValue()));
      Optional<String> maybeHash = filesystem.readFileIfItExists(hashPath);
      Preconditions.checkState(maybeHash.isPresent(), "Hash must have been computed");

      inputPathToHashMapBuilder.put(inputFilePath.toString(), maybeHash.get());
    }
  }

  /** Writes {@code relinkInfo} to the given {@code path}. */
  public static void writeConditionalLinkInfo(
      ProjectFilesystem filesystem, AbsPath path, AppleCxxConditionalLinkInfo relinkInfo)
      throws IOException {
    try (OutputStream json = filesystem.newFileOutputStream(path.getPath())) {
      ObjectMappers.WRITER.writeValue(json, relinkInfo);
    }
  }

  /**
   * For each dylib, computes the intersection between {@code allBoundSymbols} and the exported
   * symbols in the dylib.
   */
  public static ImmutableMap<String, ImmutableList<String>> computeCandidateBoundSymbols(
      ImmutableList<String> allBoundSymbols,
      ImmutableList<RelPath> dylibPaths,
      ProjectFilesystem filesystem)
      throws IOException {
    ImmutableMap.Builder<String, ImmutableList<String>> candidateSymbolsBuilder =
        ImmutableMap.builder();

    for (RelPath dylibPath : dylibPaths) {
      ImmutableList<String> candidateSymbols =
          AppleMachoSymbolBindingUtilities.computeExportedSymbolIntersection(
              allBoundSymbols, filesystem.resolve(dylibPath));
      candidateSymbolsBuilder.put(dylibPath.toString(), candidateSymbols);
    }

    return candidateSymbolsBuilder.build();
  }

  /** Reads the Mach-O UUID from an executable. */
  public static Optional<String> getMachoUUIDFromExecutable(ByteBuffer byteBuffer)
      throws Machos.MachoException {
    Optional<byte[]> uuidBytes = Machos.getUuidIfPresent(byteBuffer);
    return uuidBytes.map(bytes -> ObjectFileScrubbers.bytesToHex(bytes, true));
  }

  private Optional<Pair<String, ImmutableSet<MachoBindInfoSymbol>>>
      computeMachoUUIDAndBoundSymbolsOfLinkedExecutable() throws IOException {
    AbsPath executablePath = filesystem.resolve(linkedExecutablePath);
    try (FileChannel file = FileChannel.open(executablePath.getPath(), StandardOpenOption.READ)) {
      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

        String uuid = getMachoUUIDFromExecutable(unmapper.getByteBuffer()).get();
        ImmutableSet<MachoBindInfoSymbol> symbols =
            MachoBindInfoReader.parseStrongAndLazyBoundSymbols(unmapper.getByteBuffer());
        return Optional.of(new Pair<>(uuid, symbols));
      } catch (Machos.MachoException e) {
        LOG.error(
            String.format(
                "Failed to read Mach-O executable for relinking determination, path: '%s', exception: '%s'",
                linkedExecutablePath.toString(), e.toString()));
        return Optional.empty();
      }
    }
  }

  private static boolean isLinkableInputFileDylib(AbsPath path) throws IOException {
    String pathString = path.toString();
    // Avoid reading files if we can determine type from extension
    boolean objectCode = (pathString.endsWith(".a") || pathString.endsWith(".o"));
    if (objectCode) {
      return false;
    }

    try (FileChannel dylibChannel = FileChannel.open(path.getPath(), StandardOpenOption.READ)) {
      return Machos.isMacho(dylibChannel);
    }
  }
}
