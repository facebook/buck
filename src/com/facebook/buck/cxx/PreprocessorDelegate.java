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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SupportsColorsInOutput;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for handling preprocessing related tasks of a cxx compilation rule.
 */
class PreprocessorDelegate implements RuleKeyAppendable {

  // Fields that are added to rule key as is.
  private final Preprocessor preprocessor;
  private final Optional<SourcePath> prefixHeader;
  private final ImmutableList<CxxHeaders> includes;
  private final RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction;

  // Fields that added to the rule key with some processing.
  private final CxxToolFlags preprocessorFlags;
  private final ImmutableSet<FrameworkPath> frameworkRoots;

  // Fields that are not added to the rule key.
  private final ImmutableSet<Path> includeRoots;
  private final ImmutableSet<Path> systemIncludeRoots;
  private final ImmutableSet<Path> headerMaps;
  private final DebugPathSanitizer sanitizer;
  private final Path workingDir;
  private final SourcePathResolver resolver;
  private final Function<Path, Path> minLengthPathRepresentation = new Function<Path, Path>() {
    @Override
    public Path apply(Path path) {
      Preconditions.checkState(
          path.isAbsolute(),
          "Expected preprocessor suffix to be absolute: %s",
          path);
      String absoluteString = path.toString();
      Path relativePath = MorePaths.relativize(workingDir, path);
      String relativeString = relativePath.toString();
      return absoluteString.length() > relativeString.length() ? relativePath : path;
    }
  };
  private final Supplier<ImmutableMap<Path, Path>> replacementPaths;
  private final Supplier<ImmutableMultimap<String, SourcePath>> pathToSourcePathMap;

  public PreprocessorDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      Path workingDir,
      Preprocessor preprocessor,
      CxxToolFlags preprocessorFlags,
      Set<Path> includeRoots,
      Set<Path> systemIncludeRoots,
      Set<Path> headerMaps,
      Set<FrameworkPath> frameworkRoots,
      RuleKeyAppendableFunction<FrameworkPath, Path> frameworkPathSearchPathFunction,
      Optional<SourcePath> prefixHeader,
      List<CxxHeaders> includes) {
    this.preprocessor = preprocessor;
    this.prefixHeader = prefixHeader;
    this.includes = ImmutableList.copyOf(includes);
    this.preprocessorFlags = preprocessorFlags;
    this.frameworkRoots = ImmutableSet.copyOf(frameworkRoots);
    this.includeRoots = ImmutableSet.copyOf(includeRoots);
    this.systemIncludeRoots = ImmutableSet.copyOf(systemIncludeRoots);
    this.headerMaps = ImmutableSet.copyOf(headerMaps);
    this.sanitizer = sanitizer;
    this.workingDir = workingDir;
    this.resolver = resolver;
    this.frameworkPathSearchPathFunction = frameworkPathSearchPathFunction;

    this.replacementPaths = MoreSuppliers.weakMemoize(new ReplacementPathsSupplier());
    this.pathToSourcePathMap = MoreSuppliers.weakMemoize(new PathToSourcePathMapSupplier());
  }

  public Preprocessor getPreprocessor() {
    return preprocessor;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("preprocessor", preprocessor);
    builder.setReflectively("prefixHeader", prefixHeader);
    builder.setReflectively("includes", includes);
    builder.setReflectively("frameworkPathSearchPathFunction", frameworkPathSearchPathFunction);

    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    builder.setReflectively(
        "platformPreprocessorFlags",
        sanitizer.sanitizeFlags(preprocessorFlags.getPlatformFlags()));
    builder.setReflectively(
        "rulePreprocessorFlags",
        sanitizer.sanitizeFlags(preprocessorFlags.getRuleFlags()));

    builder.setReflectively("frameworkRoots", frameworkRoots);
    return builder;
  }

  /**
   * Resolve the map of symlinks to real paths to hand off the preprocess step.
   */
  public ImmutableMap<Path, Path> getReplacementPaths()
      throws CxxHeaders.ConflictingHeadersException {
    try {
      return replacementPaths.get();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        Throwables.propagateIfInstanceOf(cause, CxxHeaders.ConflictingHeadersException.class);
      }
      throw e;
    }
  }

  /**
   * Get the command for standalone preprocessor calls.
   *
   * @param compilerFlags flags to append.
   */
  public ImmutableList<String> getCommand(CxxToolFlags compilerFlags) {
    return ImmutableList.<String>builder()
        .addAll(preprocessor.getCommandPrefix(resolver))
        .addAll(CxxToolFlags.concat(getFlagsWithSearchPaths(), compilerFlags).getAllFlags())
        .addAll(preprocessor.getExtraFlags().or(ImmutableList.<String>of()))
        .build();
  }

  public ImmutableMap<String, String> getEnvironment() {
    return preprocessor.getEnvironment(resolver);
  }

  public CxxToolFlags getFlagsWithSearchPaths() {
    return CxxToolFlags.concat(preprocessorFlags, getSearchPathFlags());
  }

  /**
   * Flags derived from search paths.
   */
  private CxxToolFlags getSearchPathFlags() {
    // It doesn't matter whether these go in platform or rules, since they are strictly additive.
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-include"),
                FluentIterable.from(prefixHeader.asSet())
                    .transform(resolver.getAbsolutePathFunction())
                    .transform(Functions.toStringFunction())))
        .addAllRuleFlags(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(
                    headerMaps,
                    Functions.compose(Functions.toStringFunction(), minLengthPathRepresentation))))
        .addAllRuleFlags(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(
                    includeRoots,
                    Functions.compose(Functions.toStringFunction(), minLengthPathRepresentation))))
        .addAllRuleFlags(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(
                    systemIncludeRoots,
                    Functions.compose(Functions.toStringFunction(), minLengthPathRepresentation))))
        .addAllRuleFlags(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-F"),
                FluentIterable.from(frameworkRoots)
                    .transform(frameworkPathSearchPathFunction)
                    .transform(Functions.toStringFunction())
                    .toSortedSet(Ordering.natural())))
        .build();
  }

  /**
   * Get custom line processor for preprocessor output for use when running the step.
   */
  public Optional<Function<String, Iterable<String>>> getPreprocessorExtraLineProcessor() {
    return preprocessor.getExtraLineProcessor();
  }

  /**
   * @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey
   */
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(List<String> depFileLines) {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // Add inputs that we always use.
    inputs.addAll(preprocessor.getInputs());
    if (prefixHeader.isPresent()) {
      inputs.add(prefixHeader.get());
    }

    // Add any header/include inputs that our dependency file said we used.
    //
    // TODO(#9117006): We need to find out which `SourcePath` each line in the dep file refers to.
    // Since we force our compilation process to refer to headers using relative paths,
    // which results in dep files containing relative paths, we can't always get this 100%
    // correct (e.g. there may be two `SourcePath` includes with the same relative path, but
    // coming from different cells).  Favor correctness in this case and just add *all*
    // `SourcePath`s that have relative paths matching those specific in the dep file.
    ImmutableMultimap<String, SourcePath> pathToSourcePathMap = this.pathToSourcePathMap.get();
    for (String line : depFileLines) {
      inputs.addAll(pathToSourcePathMap.get(line));
    }

    return inputs.build();
  }

  public Optional<SupportsColorsInOutput> getColorSupport() {
    return Optionals.cast(preprocessor, SupportsColorsInOutput.class);
  }

  private class ReplacementPathsSupplier implements Supplier<ImmutableMap<Path, Path>> {
    @Override
    public ImmutableMap<Path, Path> get() {
      try {
        ImmutableMap.Builder<Path, Path> replacementPathsBuilder = ImmutableMap.builder();
        for (Map.Entry<Path, SourcePath> entry :
            CxxHeaders.concat(includes).getFullNameToPathMap().entrySet()) {
          // TODO(#9117006): We don't currently support a way to serialize `SourcePath` objects in a
          // cache-compatible format, and we certainly can't use absolute paths here.  So, for now,
          // just use relative paths.  The consequence here is that debug paths and error/warning
          // messages may be incorrect when referring to headers in another cell.
          replacementPathsBuilder.put(
              Preconditions.checkNotNull(minLengthPathRepresentation.apply(entry.getKey())),
              resolver.getRelativePath(entry.getValue()));
        }
        return replacementPathsBuilder.build();
      } catch (CxxHeaders.ConflictingHeadersException e) {
        throw new RuntimeException(e);
      }
    }
  }


  private class PathToSourcePathMapSupplier
      implements Supplier<ImmutableMultimap<String, SourcePath>> {
    @Override
    public ImmutableMultimap<String, SourcePath> get() {
      try {
        return Multimaps.index(
            CxxHeaders.concat(includes).getFullNameToPathMap().values(),
            Functions.compose(
                Functions.toStringFunction(),
                resolver.getRelativePathFunction()));
      } catch (CxxHeaders.ConflictingHeadersException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
