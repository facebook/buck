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
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;

/**
 * Implements the logic to determine whether linking can be skipped if the linked dylibs changed in
 * a compatible way.
 */
public class AppleMachoCxxConditionalLinkCheck extends AbstractExecutionStep {

  private static final Logger LOG = Logger.get(AppleMachoCxxConditionalLinkCheck.class);

  private final ProjectFilesystem filesystem;
  private final SourcePathResolverAdapter sourcePathResolver;
  private final ImmutableMap<SourcePath, SourcePath> sourcePaths;
  private final AbsPath argfilePath;
  private final AbsPath filelistPath;
  private final AbsPath infoPath;
  private final AbsPath skipLinkingPath;
  private final RelPath linkedExecutablePath;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> commandPrefix;
  private final boolean fallback;

  AppleMachoCxxConditionalLinkCheck(
      ProjectFilesystem filesystem,
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableMap<SourcePath, SourcePath> sourcePaths,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath infoPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> commandPrefix,
      boolean fallback) {
    super("apple-conditional-link-check");
    Preconditions.checkArgument(commandPrefix.size() > 0);
    this.filesystem = filesystem;
    this.sourcePathResolver = sourcePathResolver;
    this.sourcePaths = sourcePaths;
    this.argfilePath = argfilePath;
    this.filelistPath = filelistPath;
    this.infoPath = infoPath;
    this.skipLinkingPath = skipLinkingPath;
    this.linkedExecutablePath = linkedExecutablePath;
    this.environment = environment;
    this.commandPrefix = commandPrefix;
    this.fallback = fallback;
  }

  private Optional<String> getLinkedExecutableUUID() throws IOException {
    AbsPath executablePath = filesystem.resolve(linkedExecutablePath);
    try (FileChannel file = FileChannel.open(executablePath.getPath(), StandardOpenOption.READ)) {
      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

        return AppleMachoConditionalLinkWriteInfo.getMachoUUIDFromExecutable(
            unmapper.getByteBuffer());
      } catch (Machos.MachoException e) {
        LOG.error(
            String.format(
                "Could not extract UUID from Macho-O executable at '%s'",
                linkedExecutablePath.toString()));
        return Optional.empty();
      }
    }
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    if (!filesystem.exists(infoPath.getPath()) || !filesystem.exists(linkedExecutablePath)) {
      return StepExecutionResults.SUCCESS;
    }

    // = Correctness of Conditional Relinking =
    //
    // == Overall Idea ==
    //
    // The idea behind conditional relinking is quite simple: if we're linking against dylibs and
    // those dylibs change in an ABI-compatible way, such that all bound symbols would still resolve
    // to the exact same dylibs, then we do not have to relink.
    //
    // For example, let's suppose we have a binary A that links against B.dylib and only binds to
    // a single exported symbol: symbol_b(). Imagine that B.dylib now adds another exported symbol,
    // symbol_c() and binary A does not have any references to symbol_c(): in that case, linking
    // can be skipped.
    //
    // The major benefit of conditional relinking is to avoid relinking of very large binaries
    // when doing incremental builds. This usually arises when large applications are split into
    // smaller dylibs to speed up local development without strict barriers for symbol visibility
    // across the dylibs.
    //
    // == Technical Details ==
    //
    // There are several requirements for conditional relinking to apply. Roughly, it applies
    // only when on subsequent linker invocations if the commands are deemed "the same" _except_ the
    // dylibs being linked. "Same" is defined as:
    //
    // - All the arguments passed to the linker are the same (contents + order) - This means
    // checking the contents of the argfile, filelist + command prefix - The linker binary is the
    // same (see Linker Identity section for more) - Every input file, including object files and
    // static libraries, are _exactly_ the same - The significant of same object files and static
    // libraries is that it implies that the executable being linked _will_ not bind to any
    // additional exported symbols.
    //
    // If all of the above hold, then we know we have an incremental build where only the linked
    // dylibs have changed. We can then perform the "Relink Check" to determine if linking can be
    // skipped altogether.
    //
    // Note that, as per Buck's operation, all linked dylibs (except system frameworks) will be
    // passed as file arguments (and not using "-L/some/directory -lname").
    //
    // === Relink Check ===
    //
    // As mentioned in the section above, the idea behind the relink check is that if any linked
    // dylibs have changed in a ABI-compatible way and all bound symbols would resolve to the
    // previously bound dylibs
    //
    // 1. ABI-compatible Changes: A dylib can change in backwards compatible way. For example,
    // major OS releases add new exported symbols but executables linked against the old version
    // continue to work when ran against the new one.
    //
    // 2. Symbol Resolution: We need to check that any bound symbols would resolve to the same
    // dylib as indicated by the binding info. This arises when multiple dylibs implement the same
    // symbol whereby the linker picks the first dylib that appears in the arguments.
    //
    // ==== Check Algorithm ====
    //
    // After initial link:
    //
    // 1. Compute the executable's symbols which are bound externally (including system libs).
    //
    // 2. For each dylib, compute intersection of the bound symbols from step 1 and the exported
    //    symbols for the dylib.
    //
    //
    // Every subsequent link:
    //
    // 3. For each changed dylib, recompute the intersection from the symbols from step 1 and the
    // _currently_ exported symbols from the dylib.
    //
    // 4. If any current intersection changed from the
    // intersection recorded in the initial link, then the binary needs to be relinked.
    //
    // ==== Algorithm Explanation ====
    //
    // Intuitively, the algorithm checks for movement of exported symbols between dylibs that
    // _could_ end up changing the resolved dylibs for the bound symbols.
    //
    // The algorithm is conservative: every change that _must_ trigger a relink will do so but
    // there could be changes that will trigger relink unnecessarily. The cases where relinks would
    // be unnecessary are expected to be extremely rare as they only occur when incremental builds
    // end up adding duplicate exported symbols.
    //
    // Let's assume that a symbol needs to now resolve to a different dylib. That means that the
    // symbol will now resolve to a dylib that appears _before_ or _after_ the currently resolved
    // dylib.
    //
    // - Before Current Dylib: As system frameworks cannot change, this implies that one of the
    // other dylibs that appear as a link argument must have now added a new exported symbol that
    // was not there previously (if it was there previously, the linker would bound the symbol to
    // the earlier dylib).
    //
    // - After Current Dylib: This implies that the symbol no longer exists in
    // the currently bound dylib.
    //
    // Both cases are detected by the link check.
    //
    // ==== Examples ====
    //
    // To demonstrate the correctness of the algorithm, we'll assume the following linker
    // invocation:
    //
    //   linker ... [system libs] libA.dylib [system libs] libB.dylib [system libs] libC.dylib
    //
    // When the linker ran, the symbols were bound as follows:
    //
    //   symbol_a() -> libA.dylib
    //   symbol_b() -> libB.dylib
    //   symbol_c() -> libC.dylib
    //
    // Also assume that each dylib has the following symbols:
    //
    //   libA.dylib: symbol_a(), symbol_d()
    //   libB.dylib: symbol_b(), symbol_a()
    //   libC.dylib: symbol_c(), symbol_f()
    //
    // NOTE: Both libA.dylib and libB.dylib contain symbol_a() but because libA.dylib appears
    // first in the linker arguments, that's where the symbol gets resolved.
    //
    // The output of step 2 for each dylib:
    //
    //   - libA.dylib: symbol_a()
    //   - libB.dylib: symbol_b(), symbol_a()
    //   - libC.dylib: symbol_c()
    //
    // ==== Example 1 =====
    //
    // Let's assume that libA.dylib changed, so that symbol_a() was removed. The algorithm will
    // recompute the intersection between {symbol_a(), symbol_b(), symbol_c()} and {symbol_d()} and
    // the result would be the empty set.
    //
    // Because the empty set is not equal to {symbol_a()} (computed set in initial link), the
    // executable would be relinked.
    //
    // After the relink, symbol_a() would now resolve to libB.dylib.
    //
    // ==== Example 2 =====
    //
    // Let's assume that libA.dylib changed, so that symbol_c() was added. The algorithm will
    // recompute the intersection between {symbol_a(), symbol_b(), symbol_c()} and {symbol_a(),
    // symbol_c(), symbol_d()} and the result would be {symbol_a(), symbol_c()}.
    //
    // Because the result is not equal to {symbol_a()} (computed set in initial link), the
    // executable would be relinked.
    //
    // After the relink, symbol_c() would now resolve to libA.dylib.
    //
    // ==== Example 3 ====
    //
    // This is an example of the conservative nature of the algorithm where it would relink
    // unnecessarily.
    //
    // Suppose symbol_a() gets added to libC.dylib - that would result in a relink even though
    // symbol_a() would still resolve to libA.dylib.
    //
    // While this inefficiency can be fixed, it would complicate the algorithm and implementation
    // beyond the expected benefits of handling such cases which involve duplicate symbols being
    // added during incremental builds.
    //
    // == Two-Level vs Flat Namespaces ==
    //
    // The algorithm outlined above works with both flat and two-level namespaced executables.
    // That's because we do not need to record the precise association of bound symbols to libraries
    // which is not present for flat namespaced executables.
    //
    // Even though flat namespacing is deprecated on mobile platforms (iOS/tvOS/watchOS/iPadOS),
    // flat lookup symbols can still exist in a two-level namespaced executable because of
    // dynamic lookup symbols (-undefined or -U).
    //
    // == Linker Identity ==
    //
    // It should be noted that the equality checks for the linker identity can be circumvented.
    // Even Buck itself suffers from inability to properly determine all the state that goes into
    // the identity computation for the linker.
    //
    // For example, /usr/lib/clang is just a shim that redirects to the currently selected Xcode
    // install. But Buck has no way to know that. Even if it did, it cannot make general assumptions
    // about how the linker operates (e.g., there might be magic files on the filesystem that affect
    // its operation, etc). Furthermore, linker can be overridden using -fuse-ld=path and Buck
    // itself
    // will not follow and hash the contents of the given path (and the overridden linker itself
    // might be a script that redirects to other linkers - this is a real world scenario).
    //
    // In practice, making sure the linker identity check is correct is a matter of ensuring
    // Xcode installs have different names (even if system shim is used because -isysroot path is
    // still passed). This can also be done by tooling at the layer above (e.g., project generation)
    // which can force a non-conditional rebuild.
    //
    // == Optimisation ==
    //
    // There's an optimisation opportunity when computing the sets of bound symbols. Rather than
    // using all the bound symbols, we can restrict it to symbols present in the dylibs online.
    //
    // The benefit would be a reduction of names ot check but testing reveals that the savings to
    // minimal (in the order of tens of milliseconds). The downside is that adding any symbols
    // reimplemented in the dylibs which have the same name as system symbol names would not trigger
    // relinking. We prefer correctness in this case.

    if (fallback
        && filesystem.exists(infoPath.getPath())
        && filesystem.getFileSize(infoPath.getPath()) == 0) {
      // If fallback behavior is enabled and writing failed, we expect to see an empty file.
      // Not writing the signal file here means fallback to standard linking.
      return StepExecutionResults.SUCCESS;
    }

    AppleCxxConditionalLinkInfo relinkInfo =
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(infoPath.getPath()),
            ImmutableAppleCxxConditionalLinkInfo.class);

    if (relinkInfo.getVersion() != AppleCxxConditionalLinkInfo.CURRENT_VERSION) {
      return StepExecutionResults.SUCCESS;
    }

    // Firstly, check if the info UUID matches the executable

    Optional<String> maybeUUID = getLinkedExecutableUUID();
    if (!maybeUUID.isPresent() || !maybeUUID.get().equals(relinkInfo.getExecutableUUID())) {
      return StepExecutionResults.SUCCESS;
    }

    // Check if the argfile + file list hashes are the same

    String argfileHash = filesystem.computeSha1(argfilePath.getPath()).getHash();
    if (!argfileHash.equals(relinkInfo.getLinkerArgfileHash())) {
      return StepExecutionResults.SUCCESS;
    }

    String filelistHash = filesystem.computeSha1(filelistPath.getPath()).getHash();
    if (!filelistHash.equals(relinkInfo.getLinkerFilelistHash())) {
      return StepExecutionResults.SUCCESS;
    }

    // Check the linker environment, command prefix

    if (!environment.equals(relinkInfo.getLinkerEnvironment())) {
      return StepExecutionResults.SUCCESS;
    }

    if (!commandPrefix.equals(relinkInfo.getLinkerCommandPrefix())) {
      return StepExecutionResults.SUCCESS;
    }

    // Check whether all files except dylibs have the same hashes

    if (sourcePaths.size() != relinkInfo.getLinkerInputFileToHash().size()) {
      return StepExecutionResults.SUCCESS;
    }

    ImmutableSet<String> previousDylibPathsSet = ImmutableSet.copyOf(relinkInfo.getDylibs());
    ImmutableMap<String, String> previousFilePathHashes = relinkInfo.getLinkerInputFileToHash();
    ImmutableMap.Builder<String, String> currentFilePathHashes = ImmutableMap.builder();

    ImmutableList.Builder<RelPath> changedDylibPaths = ImmutableList.builder();

    for (Map.Entry<SourcePath, SourcePath> fileToHashEntry : sourcePaths.entrySet()) {
      RelPath inputFilePath =
          filesystem.relativize(sourcePathResolver.getAbsolutePath(fileToHashEntry.getKey()));
      String inputFilePathString = inputFilePath.toString();
      if (!previousFilePathHashes.containsKey(inputFilePathString)) {
        // New input file to the linker, need to relink
        return StepExecutionResults.SUCCESS;
      }

      RelPath hashPath =
          filesystem.relativize(sourcePathResolver.getAbsolutePath(fileToHashEntry.getValue()));
      Optional<String> maybeHash = filesystem.readFileIfItExists(hashPath);
      Preconditions.checkState(maybeHash.isPresent(), "Hash must have been computed");

      String previousHash = previousFilePathHashes.get(inputFilePathString);
      String currentHash = maybeHash.get();
      boolean isFileDylib = previousDylibPathsSet.contains(inputFilePathString);
      boolean fileHasChanged = !previousHash.equals(currentHash);

      if (fileHasChanged) {
        if (isFileDylib) {
          // Different file and it's a dylib, we can perform more granular checking
          changedDylibPaths.add(inputFilePath);
        } else {
          // File changed and it's not a dylib, need to relink
          return StepExecutionResults.SUCCESS;
        }
      }

      currentFilePathHashes.put(inputFilePathString, currentHash);
    }

    // Compute candidate symbol binding using the information from the current dylibs and compare
    // against the same computation from the previous link.

    Optional<ImmutableMap<String, ImmutableList<String>>> maybeChangedDylibsCandidateBoundSymbols =
        AppleMachoConditionalLinkWriteInfo.computeCandidateBoundSymbols(
            relinkInfo.getExecutableBoundSymbols(), changedDylibPaths.build(), filesystem);

    ImmutableMap<String, ImmutableList<String>> previousCandidateBoundSymbols =
        relinkInfo.getCandidateBoundSymbols();

    if (maybeChangedDylibsCandidateBoundSymbols.isPresent()
        && boundSymbolSetsAreEqual(
            maybeChangedDylibsCandidateBoundSymbols.get(), previousCandidateBoundSymbols)) {
      // Relinking can be skipped as there's no movement of bound symbols within the dylibs,
      // need to update info with the latest input file hashes +
      AppleCxxConditionalLinkInfo updatedInfo =
          createRelinkInfoWithLatestHashes(relinkInfo, currentFilePathHashes.build());
      AppleMachoConditionalLinkWriteInfo.writeConditionalLinkInfo(
          filesystem, infoPath, updatedInfo);

      // Write "signal" file to skip linking, the linking step checks this file and if it exists,
      // will skip invoking the linker.
      filesystem.touch(skipLinkingPath.getPath());
    }

    return StepExecutionResults.SUCCESS;
  }

  private static AppleCxxConditionalLinkInfo createRelinkInfoWithLatestHashes(
      AppleCxxConditionalLinkInfo relinkInfo, ImmutableMap<String, String> latestHashes) {
    Preconditions.checkArgument(
        relinkInfo.getLinkerInputFileToHash().keySet().equals(latestHashes.keySet()));
    // Only need to update the hashes because everything else has stayed the same
    return AppleCxxConditionalLinkInfo.of(
        relinkInfo.getExecutableUUID(),
        latestHashes,
        relinkInfo.getLinkerArgfileHash(),
        relinkInfo.getLinkerFilelistHash(),
        relinkInfo.getExecutableBoundSymbols(),
        relinkInfo.getDylibs(),
        relinkInfo.getCandidateBoundSymbols(),
        relinkInfo.getLinkerEnvironment(),
        relinkInfo.getLinkerCommandPrefix());
  }

  private static boolean boundSymbolSetsAreEqual(
      ImmutableMap<String, ImmutableList<String>> changedDylibsCandidateBoundSymbols,
      ImmutableMap<String, ImmutableList<String>> previousCandidateBoundSymbols) {
    for (String lib : changedDylibsCandidateBoundSymbols.keySet()) {
      if (!previousCandidateBoundSymbols.containsKey(lib)) {
        LOG.error("Found changed dylib that's not part of previous, internal logic error");
        return false;
      }

      // We cannot rely on the ordering of the symbols, that's why we need to use set semantics
      ImmutableSet<String> changedSymbolSet =
          ImmutableSet.copyOf(changedDylibsCandidateBoundSymbols.get(lib));
      ImmutableSet<String> previousSymbolSet =
          ImmutableSet.copyOf(previousCandidateBoundSymbols.get(lib));
      if (!changedSymbolSet.equals(previousSymbolSet)) {
        // Movement of symbols bound to dylibs, must be relinked
        return false;
      }
    }

    return true;
  }
}
