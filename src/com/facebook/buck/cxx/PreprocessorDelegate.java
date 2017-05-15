/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Helper class for handling preprocessing related tasks of a cxx compilation rule. */
final class PreprocessorDelegate implements RuleKeyAppendable {

  // Fields that are added to rule key as is.
  private final Preprocessor preprocessor;
  private final RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction;
  private final HeaderVerification headerVerification;

  // Fields that added to the rule key with some processing.
  private final PreprocessorFlags preprocessorFlags;

  // Fields that are not added to the rule key.
  private final DebugPathSanitizer sanitizer;
  private final Path workingDir;
  private final SourcePathResolver resolver;
  private final Optional<SymlinkTree> sandbox;

  /**
   * If present, these paths will be added first (prior to the current rule's list of paths) when
   * building the list of compiler flags, in {@link #getFlagsWithSearchPaths(Optional)}.
   */
  private final Optional<CxxIncludePaths> leadingIncludePaths;

  private final PathShortener minLengthPathRepresentation;

  private final Supplier<HeaderPathNormalizer> headerPathNormalizer =
      MoreSuppliers.weakMemoize(
          new Supplier<HeaderPathNormalizer>() {
            @Override
            public HeaderPathNormalizer get() {
              HeaderPathNormalizer.Builder builder = new HeaderPathNormalizer.Builder(resolver);
              for (CxxHeaders include : preprocessorFlags.getIncludes()) {
                include.addToHeaderPathNormalizer(builder);
              }
              for (FrameworkPath frameworkPath : preprocessorFlags.getFrameworkPaths()) {
                frameworkPath.getSourcePath().ifPresent(builder::addHeaderDir);
              }
              if (preprocessorFlags.getPrefixHeader().isPresent()) {
                SourcePath headerPath = preprocessorFlags.getPrefixHeader().get();
                builder.addPrefixHeader(headerPath);
              }
              if (sandbox.isPresent()) {
                ExplicitBuildTargetSourcePath root =
                    new ExplicitBuildTargetSourcePath(
                        sandbox.get().getBuildTarget(), sandbox.get().getRoot());
                builder.addSymlinkTree(root, sandbox.get().getLinks());
              }
              return builder.build();
            }
          });

  public PreprocessorDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      HeaderVerification headerVerification,
      Path workingDir,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessorFlags,
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction,
      Optional<SymlinkTree> sandbox,
      Optional<CxxIncludePaths> leadingIncludePaths)
      throws ConflictingHeadersException {
    this.preprocessor = preprocessor;
    this.preprocessorFlags = preprocessorFlags;
    this.sanitizer = sanitizer;
    this.headerVerification = headerVerification;
    this.workingDir = workingDir;
    this.minLengthPathRepresentation = PathShortener.byRelativizingToWorkingDir(workingDir);
    this.resolver = resolver;
    this.frameworkPathSearchPathFunction = frameworkPathSearchPathFunction;
    this.sandbox = sandbox;
    this.leadingIncludePaths = leadingIncludePaths;

    checkForConflictingHeaders();
  }

  public PreprocessorDelegate withLeadingIncludePaths(CxxIncludePaths leadingIncludePaths)
      throws ConflictingHeadersException {
    return new PreprocessorDelegate(
        this.resolver,
        this.sanitizer,
        this.headerVerification,
        this.workingDir,
        this.preprocessor,
        this.preprocessorFlags,
        this.frameworkPathSearchPathFunction,
        this.sandbox,
        Optional.of(leadingIncludePaths));
  }

  public Preprocessor getPreprocessor() {
    return preprocessor;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("preprocessor", preprocessor);
    sink.setReflectively("frameworkPathSearchPathFunction", frameworkPathSearchPathFunction);
    sink.setReflectively("headerVerification", headerVerification);
    preprocessorFlags.appendToRuleKey(sink, sanitizer);
  }

  public HeaderPathNormalizer getHeaderPathNormalizer() {
    return headerPathNormalizer.get();
  }

  /**
   * Get the command for standalone preprocessor calls.
   *
   * @param compilerFlags flags to append.
   */
  public ImmutableList<String> getCommand(
      CxxToolFlags compilerFlags, Optional<CxxPrecompiledHeader> pch) {
    return ImmutableList.<String>builder()
        .addAll(getCommandPrefix())
        .addAll(getArguments(compilerFlags, pch))
        .build();
  }

  public ImmutableList<String> getCommandPrefix() {
    return preprocessor.getCommandPrefix(resolver);
  }

  public ImmutableList<String> getArguments(
      CxxToolFlags compilerFlags, Optional<CxxPrecompiledHeader> pch) {
    return ImmutableList.copyOf(
        CxxToolFlags.concat(getFlagsWithSearchPaths(pch), compilerFlags).getAllFlags());
  }

  public ImmutableMap<String, String> getEnvironment() {
    return preprocessor.getEnvironment(resolver);
  }

  public CxxToolFlags getFlagsWithSearchPaths(Optional<CxxPrecompiledHeader> pch) {
    CxxToolFlags leadingFlags;
    if (leadingIncludePaths.isPresent()) {
      leadingFlags =
          leadingIncludePaths
              .get()
              .toToolFlags(
                  resolver,
                  minLengthPathRepresentation,
                  frameworkPathSearchPathFunction,
                  preprocessor);
    } else {
      leadingFlags = CxxToolFlags.of();
    }

    return CxxToolFlags.concat(
        leadingFlags,
        preprocessorFlags.toToolFlags(
            resolver,
            minLengthPathRepresentation,
            frameworkPathSearchPathFunction,
            preprocessor,
            pch));
  }

  /**
   * Get all the preprocessor's include paths.
   *
   * @see PreprocessorFlags#getCxxIncludePaths()
   */
  public CxxIncludePaths getCxxIncludePaths() {
    return preprocessorFlags.getCxxIncludePaths();
  }

  public CxxToolFlags getNonIncludePathFlags(Optional<CxxPrecompiledHeader> pch) {
    return preprocessorFlags.getNonIncludePathFlags(resolver, pch, preprocessor);
  }

  /**
   * Build a {@link CxxToolFlags} representing our include paths (local, system, iquote, framework).
   * Does not include {@link #leadingIncludePaths}.
   */
  public CxxToolFlags getIncludePathFlags() {
    return preprocessorFlags.getIncludePathFlags(
        resolver, minLengthPathRepresentation, frameworkPathSearchPathFunction, preprocessor);
  }

  private void checkForConflictingHeaders() throws ConflictingHeadersException {
    Map<Path, SourcePath> headers = new HashMap<>();
    for (CxxHeaders cxxHeaders : this.preprocessorFlags.getIncludes()) {
      if (cxxHeaders instanceof CxxSymlinkTreeHeaders) {
        CxxSymlinkTreeHeaders symlinkTreeHeaders = (CxxSymlinkTreeHeaders) cxxHeaders;
        for (Map.Entry<Path, SourcePath> entry : symlinkTreeHeaders.getNameToPathMap().entrySet()) {
          SourcePath original = headers.put(entry.getKey(), entry.getValue());
          if (original != null && !original.equals(entry.getValue())) {
            throw new ConflictingHeadersException(entry.getKey(), original, entry.getValue());
          }
        }
      }
    }
  }

  /** @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey */
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(Iterable<Path> depFileLines) {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // Add inputs that we always use.
    inputs.addAll(preprocessor.getInputs());

    // Prefix header is not represented in the dep file, so should be added manually.
    if (preprocessorFlags.getPrefixHeader().isPresent()) {
      inputs.add(preprocessorFlags.getPrefixHeader().get());
    }

    // Add any header/include inputs that our dependency file said we used.
    //
    // TODO(#9117006): We need to find out which `SourcePath` each line in the dep file refers to.
    // Since we force our compilation process to refer to headers using relative paths,
    // which results in dep files containing relative paths, we can't always get this 100%
    // correct (e.g. there may be two `SourcePath` includes with the same relative path, but
    // coming from different cells).  Favor correctness in this case and just add *all*
    // `SourcePath`s that have relative paths matching those specific in the dep file.
    HeaderPathNormalizer headerPathNormalizer = getHeaderPathNormalizer();
    for (Path absolutePath : depFileLines) {
      Preconditions.checkState(absolutePath.isAbsolute());
      inputs.add(headerPathNormalizer.getSourcePathForAbsolutePath(absolutePath));
    }

    return inputs.build();
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    // TODO(jkeljo): I didn't know how to implement this, and didn't have time to figure it out.
    return (SourcePath path) -> true;
  }

  public HeaderVerification getHeaderVerification() {
    return headerVerification;
  }

  public Optional<SourcePath> getPrefixHeader() {
    return preprocessorFlags.getPrefixHeader();
  }

  /**
   * Generate a digest of the compiler flags.
   *
   * <p>Generated PCH files can only be used when compiling with similar compiler flags. This
   * guarantees the uniqueness of the generated file.
   *
   * <p>Note: when building the hash for identifying the PCH itself, just pass {@code
   * Optional.empty()}.
   */
  public String hashCommand(CxxToolFlags flags, Optional<CxxPrecompiledHeader> pch) {
    return hashCommand(getCommand(flags, pch));
  }

  public String hashCommand(ImmutableList<String> flags) {
    Hasher hasher = Hashing.murmur3_128().newHasher();
    String workingDirString = workingDir.toString();
    // Skips the executable argument (the first one) as that is not sanitized.
    for (String part : sanitizer.sanitizeFlags(Iterables.skip(flags, 1))) {
      // TODO(#10251354): find a better way of dealing with getting a project dir normalized hash
      if (part.startsWith(workingDirString)) {
        part = "<WORKINGDIR>" + part.substring(workingDirString.length());
      }
      hasher.putString(part, Charsets.UTF_8);
      hasher.putBoolean(false); // separator
    }
    return hasher.hash().toString();
  }

  @SuppressWarnings("serial")
  public static class ConflictingHeadersException extends Exception {
    public ConflictingHeadersException(Path key, SourcePath value1, SourcePath value2) {
      super(String.format("'%s' maps to both %s.", key, ImmutableSortedSet.of(value1, value2)));
    }

    public HumanReadableException getHumanReadableExceptionForBuildTarget(BuildTarget buildTarget) {
      return new HumanReadableException(
          this, "Target '%s' uses conflicting header file mappings. %s", buildTarget, getMessage());
    }
  }

  public PreprocessorFlags getPreprocessorFlags() {
    return preprocessorFlags;
  }
}
