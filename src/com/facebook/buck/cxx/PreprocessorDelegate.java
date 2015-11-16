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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * Helper class for handling preprocessing related tasks of a cxx compilation rule.
 */
class PreprocessorDelegate implements RuleKeyAppendable {

  // Fields that are added to rule key as is.
  private final Preprocessor preprocessor;
  private final Optional<SourcePath> prefixHeader;
  private final ImmutableList<CxxHeaders> includes;

  // Fields that added to the rule key with some processing.
  private final ImmutableList<String> platformPreprocessorFlags;
  private final ImmutableList<String> rulePreprocessorFlags;
  private final ImmutableSet<Path> frameworkRoots;

  // Fields that are not added to the rule key.
  private final ImmutableSet<Path> includeRoots;
  private final ImmutableSet<Path> systemIncludeRoots;
  private final ImmutableSet<Path> headerMaps;
  private final DebugPathSanitizer sanitizer;
  private final SourcePathResolver resolver;

  public PreprocessorDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      Preprocessor preprocessor,
      List<String> platformPreprocessorFlags,
      List<String> rulePreprocessorFlags,
      Set<Path> includeRoots,
      Set<Path> systemIncludeRoots,
      Set<Path> headerMaps,
      Set<Path> frameworkRoots,
      Optional<SourcePath> prefixHeader,
      List<CxxHeaders> includes) {
    this.preprocessor = preprocessor;
    this.prefixHeader = prefixHeader;
    this.includes = ImmutableList.copyOf(includes);
    this.platformPreprocessorFlags = ImmutableList.copyOf(platformPreprocessorFlags);
    this.rulePreprocessorFlags = ImmutableList.copyOf(rulePreprocessorFlags);
    this.frameworkRoots = ImmutableSet.copyOf(frameworkRoots);
    this.includeRoots = ImmutableSet.copyOf(includeRoots);
    this.systemIncludeRoots = ImmutableSet.copyOf(systemIncludeRoots);
    this.headerMaps = ImmutableSet.copyOf(headerMaps);
    this.sanitizer = sanitizer;
    this.resolver = resolver;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("preprocessor", preprocessor);
    builder.setReflectively("prefixHeader", prefixHeader);
    builder.setReflectively("includes", includes);

    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    builder.setReflectively(
        "platformPreprocessorFlags",
        sanitizer.sanitizeFlags(Optional.of(platformPreprocessorFlags)));
    builder.setReflectively(
        "rulePreprocessorFlags",
        sanitizer.sanitizeFlags(Optional.of(rulePreprocessorFlags)));

    ImmutableList<String> frameworkRoots = FluentIterable.from(this.frameworkRoots)
        .transform(Functions.toStringFunction())
        .transform(sanitizer.sanitize(Optional.<Path>absent()))
        .toList();
    builder.setReflectively("frameworkRoots", frameworkRoots);
    return builder;
  }

  /**
   * Resolve the map of symlinks to real paths to hand off the preprocess step.
   */
  public ImmutableMap<Path, Path> getReplacementPaths()
      throws CxxHeaders.ConflictingHeadersException {
    ImmutableMap.Builder<Path, Path> replacementPathsBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, SourcePath> entry :
        CxxHeaders.concat(includes).getFullNameToPathMap().entrySet()) {
      replacementPathsBuilder.put(
          entry.getKey(),
          resolver.deprecatedGetPath(entry.getValue()));
    }
    return replacementPathsBuilder.build();
  }

  /**
   * Get the command for standalone preprocessor calls.
   */
  public ImmutableList<String> getPreprocessorCommand() {
    return ImmutableList.<String>builder()
        .addAll(preprocessor.getCommandPrefix(resolver))
        .addAll(getPreprocessorPlatformPrefix())
        .addAll(getPreprocessorSuffix())
        .addAll(preprocessor.getExtraFlags().or(ImmutableList.<String>of()))
        .build();

  }

  public ImmutableMap<String, String> getPreprocessorEnvironment() {
    return preprocessor.getEnvironment(resolver);
  }

  /**
   * Get platform preprocessor flags for composing into the compiler command line.
   */
  public ImmutableList<String> getPreprocessorPlatformPrefix() {
    return platformPreprocessorFlags;
  }

  /**
   * Get preprocessor flags for composing into the compiler command line that should be appended
   * after the rest.
   *
   * This is important when there are flags that overwrite previous flags.
   */
  public ImmutableList<String> getPreprocessorSuffix() {
    return ImmutableList.<String>builder()
        .addAll(rulePreprocessorFlags)
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-include"),
                FluentIterable.from(prefixHeader.asSet())
                    .transform(resolver.deprecatedPathFunction())
                    .transform(Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(headerMaps, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-F"),
                Iterables.transform(frameworkRoots, Functions.toStringFunction())))
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
  public ImmutableList<Path> getInputsAfterBuildingLocally(List<String> depFileLines) {
    ImmutableList.Builder<Path> inputs = ImmutableList.builder();
    inputs.addAll(
        Ordering.natural().immutableSortedCopy(
            resolver.deprecatedAllPaths(preprocessor.getInputs())));
    if (prefixHeader.isPresent()) {
      inputs.add(resolver.deprecatedGetPath(prefixHeader.get()));
    }
    inputs.addAll(Iterables.transform(depFileLines, MorePaths.TO_PATH));
    return inputs.build();
  }

  /**
   * @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey
   */
  public Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap(
      SortedSet<BuildRule> deps,
      List<String> depFileLines) {
    ImmutableMultimap.Builder<String, String> fullHeaderMapBuilder = ImmutableMultimap.builder();
    for (HeaderSymlinkTree headerSymlinkTree :
        Iterables.filter(deps, HeaderSymlinkTree.class)) {
      for (Map.Entry<Path, Path> entry : Maps
          .transformValues(headerSymlinkTree.getFullLinks(), resolver.deprecatedPathFunction())
          .entrySet()) {
        fullHeaderMapBuilder.put(entry.getValue().toString(), entry.getKey().toString());
      }
    }
    ImmutableMultimap<String, String> fullHeaderMap = fullHeaderMapBuilder.build();

    ImmutableMultimap.Builder<String, String> headerMap = ImmutableMultimap.builder();
    for (String input : depFileLines) {
      if (!fullHeaderMap.containsKey(input)) {
        return Optional.absent();
      }
      headerMap.putAll(input, fullHeaderMap.get(input));
    }
    return Optional.of(headerMap.build());
  }

}
