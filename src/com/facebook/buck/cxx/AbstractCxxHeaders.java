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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * Encapsulates headers from a single root location.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxHeaders implements RuleKeyAppendable {

  abstract CxxPreprocessables.IncludeType getIncludeType();

  /**
   * @return the root of the includes.
   */
  abstract SourcePath getRoot();

  /**
   * @return the path to add to the preprocessor search path to find the includes.  This defaults
   *     to the root, but can be overridden to use an alternate path.
   */
  @Value.Default
  SourcePath getIncludeRoot() {
    return getRoot();
  }

  /**
   * @return the path to the optional header map to use for this header pack.
   */
  abstract Optional<SourcePath> getHeaderMap();

  /**
   * Maps the name of the header (e.g. the path used to include it in a C/C++ source) to the
   * real location of the header.
   */
  abstract ImmutableMap<Path, SourcePath> getNameToPathMap();

  /**
   * @return all deps required by this header pack.
   */
  public Iterable<BuildRule> getDeps(SourcePathResolver resolver) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(resolver.filterBuildRuleInputs(getNameToPathMap().values()));
    deps.addAll(resolver.filterBuildRuleInputs(getRoot()));
    deps.addAll(resolver.filterBuildRuleInputs(getIncludeRoot()));
    deps.addAll(resolver.filterBuildRuleInputs(getHeaderMap().asSet()));
    return deps.build();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("type", getIncludeType());
    for (Path path : ImmutableSortedSet.copyOf(getNameToPathMap().keySet())) {
      SourcePath source = getNameToPathMap().get(path);
      builder.setReflectively("include(" + path.toString() + ")", source);
    }
    return builder;
  }

  private static String resolveSourcePath(
      SourcePathResolver resolver,
      SourcePath path,
      Optional<Function<Path, Path>> pathShortener) {
    return Preconditions.checkNotNull(
        pathShortener.or(Functions.<Path>identity())
            .apply(resolver.getAbsolutePath(path))).toString();
  }

  /**
   * @return the arguments to add to the preprocessor command line to include the given header packs
   *     in preprocessor search path.
   */
  public static Iterable<String> getArgs(
      Iterable<CxxHeaders> cxxHeaderses,
      SourcePathResolver resolver,
      Optional<Function<Path, Path>> pathShortener) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    // Collect the header maps and roots into buckets organized by include type, so that we can:
    // 1) Apply the header maps first (so that they work properly).
    // 2) De-duplicate redundant include paths.
    Multimap<CxxPreprocessables.IncludeType, String> headerMaps = LinkedHashMultimap.create();
    Multimap<CxxPreprocessables.IncludeType, String> roots = LinkedHashMultimap.create();
    for (CxxHeaders cxxHeaders : cxxHeaderses) {
      Optional<SourcePath> headerMap = cxxHeaders.getHeaderMap();
      if (headerMap.isPresent()) {
        headerMaps.put(
            cxxHeaders.getIncludeType(),
            resolveSourcePath(resolver, headerMap.get(), pathShortener));
      }
      roots.put(
          cxxHeaders.getIncludeType(),
          resolveSourcePath(resolver, cxxHeaders.getIncludeRoot(), pathShortener));
    }

    // Define the include type ordering.  We always add local ("-I") include paths first so that
    // headers match there before system ("-isystem") ones.
    ImmutableSet<CxxPreprocessables.IncludeType> includeTypes =
        ImmutableSet.of(
            CxxPreprocessables.IncludeType.LOCAL,
            CxxPreprocessables.IncludeType.SYSTEM);

    // Apply the header maps first, so that headers that matching there avoid falling back to
    // stat'ing files in the normal include roots.
    Preconditions.checkState(includeTypes.containsAll(headerMaps.keySet()));
    for (CxxPreprocessables.IncludeType includeType : includeTypes) {
      args.addAll(
          MoreIterables.zipAndConcat(
              Iterables.cycle(includeType.getFlag()),
              headerMaps.get(includeType)));
    }

    // Apply the regular includes last.
    Preconditions.checkState(includeTypes.containsAll(roots.keySet()));
    for (CxxPreprocessables.IncludeType includeType : includeTypes) {
      args.addAll(
          MoreIterables.zipAndConcat(
              Iterables.cycle(includeType.getFlag()),
              roots.get(includeType)));
    }

    return args.build();
  }

  /**
   * @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}.
   */
  public static CxxHeaders fromSymlinkTree(
      HeaderSymlinkTree symlinkTree,
      CxxPreprocessables.IncludeType includeType) {
    CxxHeaders.Builder builder = CxxHeaders.builder();
    builder.setIncludeType(includeType);
    builder.setRoot(
        new BuildTargetSourcePath(
            symlinkTree.getBuildTarget(),
            symlinkTree.getRoot()));
    builder.setIncludeRoot(
        new BuildTargetSourcePath(
            symlinkTree.getBuildTarget(),
            symlinkTree.getIncludePath()));
    builder.putAllNameToPathMap(symlinkTree.getLinks());
    if (symlinkTree.getHeaderMap().isPresent()) {
      builder.setHeaderMap(
          new BuildTargetSourcePath(
              symlinkTree.getBuildTarget(),
              symlinkTree.getHeaderMap().get()));
    }
    return builder.build();
  }

}
