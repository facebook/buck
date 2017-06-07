/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.Optional;

/** Encapsulates headers from a single root location. */
public abstract class CxxHeaders implements RuleKeyAppendable {

  public abstract CxxPreprocessables.IncludeType getIncludeType();

  /** @return the root of the includes. */
  public abstract SourcePath getRoot();

  /** @return the path to the optional header map to use for this header pack. */
  public abstract Optional<SourcePath> getHeaderMap();

  /**
   * @return the path to add to the preprocessor search path to find the includes. This defaults to
   *     the root, but can be overridden to use an alternate path.
   */
  public abstract SourcePath getIncludeRoot();

  /**
   * Add this header pack to the given {@link com.facebook.buck.cxx.HeaderPathNormalizer.Builder}.
   */
  public abstract void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder);

  /** @return all deps required by this header pack. */
  public abstract Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder);

  private static Path resolveSourcePathAndShorten(
      SourcePathResolver resolver, SourcePath path, Optional<PathShortener> pathShortener) {
    Path resolvedPath = resolver.getAbsolutePath(path);
    return pathShortener.isPresent() ? pathShortener.get().shorten(resolvedPath) : resolvedPath;
  }

  /**
   * @return the arguments to add to the preprocessor command line to include the given header packs
   *     in preprocessor search path.
   */
  public static Iterable<String> getArgs(
      Iterable<CxxHeaders> cxxHeaderses,
      SourcePathResolver resolver,
      Optional<PathShortener> pathMinimizer,
      Preprocessor preprocessor) {
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
            resolveSourcePathAndShorten(resolver, headerMap.get(), pathMinimizer).toString());
      }
      roots.put(
          cxxHeaders.getIncludeType(),
          resolveSourcePathAndShorten(resolver, cxxHeaders.getIncludeRoot(), pathMinimizer)
              .toString());
    }

    // Define the include type ordering.  We always add local ("-I") include paths first so that
    // headers match there before system ("-isystem") ones.
    ImmutableSet<CxxPreprocessables.IncludeType> includeTypes =
        ImmutableSet.of(
            CxxPreprocessables.IncludeType.LOCAL, CxxPreprocessables.IncludeType.SYSTEM);

    // Apply the header maps first, so that headers that matching there avoid falling back to
    // stat'ing files in the normal include roots.
    Preconditions.checkState(includeTypes.containsAll(headerMaps.keySet()));
    for (CxxPreprocessables.IncludeType includeType : includeTypes) {
      args.addAll(includeType.includeArgs(preprocessor, headerMaps.get(includeType)));
    }

    // Apply the regular includes last.
    Preconditions.checkState(includeTypes.containsAll(roots.keySet()));
    for (CxxPreprocessables.IncludeType includeType : includeTypes) {
      args.addAll(includeType.includeArgs(preprocessor, roots.get(includeType)));
    }

    return args.build();
  }
}
