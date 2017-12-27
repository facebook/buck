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

import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.HasCustomDepsLogic;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Encapsulates headers from a single root location. */
public abstract class CxxHeaders implements AddsToRuleKey, HasCustomDepsLogic {

  public abstract CxxPreprocessables.IncludeType getIncludeType();

  /** @return the root of the includes. */
  @Nullable
  public abstract SourcePath getRoot();

  /** @return the path to the optional header map to use for this header pack. */
  public abstract Optional<SourcePath> getHeaderMap();

  /**
   * Add this header pack to the given {@link com.facebook.buck.cxx.HeaderPathNormalizer.Builder}.
   */
  public abstract void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder);

  /** @return all deps required by this header pack. */
  @Override
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return BuildableSupport.deriveDeps(this, ruleFinder);
  }

  /**
   * @return the path to add to the preprocessor search path to find the includes. This defaults to
   *     the root, but can be overridden to use an alternate path.
   */
  public Optional<Path> getResolvedIncludeRoot(SourcePathResolver resolver) {
    return Optional.of(resolver.getAbsolutePath(Preconditions.checkNotNull(getRoot())));
  }

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
      cxxHeaders
          .getResolvedIncludeRoot(resolver)
          .ifPresent(
              resolvedPath ->
                  roots.put(
                      cxxHeaders.getIncludeType(),
                      pathMinimizer
                          .map(min -> min.shorten(resolvedPath))
                          .orElse(resolvedPath)
                          .toString()));
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

  static void checkConflictingHeaders(Iterable<CxxHeaders> allHeaders)
      throws ConflictingHeadersException {
    int estimatedSize =
        RichStream.from(allHeaders)
            .filter(CxxSymlinkTreeHeaders.class)
            .mapToInt(cxxHeaders -> cxxHeaders.getNameToPathMap().size())
            .sum();
    Map<Path, SourcePath> headers = new HashMap<>(estimatedSize);
    for (CxxHeaders cxxHeaders : allHeaders) {
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

  public static class ConflictingHeadersException extends Exception {
    public ConflictingHeadersException(Path key, SourcePath value1, SourcePath value2) {
      super(
          String.format(
              "'%s' maps to the following header files:\n"
                  + "- %s\n"
                  + "- and %s\n\n"
                  + "Please rename one of them or export one of them to a different path.",
              key, value1, value2));
    }

    public HumanReadableException getHumanReadableExceptionForBuildTarget(BuildTarget buildTarget) {
      return new HumanReadableException(
          this,
          "Target '%s' has dependencies using headers that can be included using the same path.\n\n"
              + "%s",
          buildTarget,
          getMessage());
    }
  }
}
