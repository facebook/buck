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

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A Java native glob function implementation that allows resolving file paths based on include
 * patterns (file patterns that should be returned) minus exclude patterns (file patterns that
 * should be excluded from the resulting set).
 *
 * <p>Since this is a simple implementation it does not support caching and other smarts.
 */
public class NativeGlobber implements Globber {

  /** Path used as a root when resolving patterns. */
  private final AbsPath basePath;

  private NativeGlobber(AbsPath basePath) {
    this.basePath = basePath;
  }

  /**
   * @param include File patterns that should be included in the resulting set.
   * @param exclude File patterns that should be excluded from the resulting set.
   * @param excludeDirectories Whether directories should be excluded from the resulting set.
   * @return The set of paths resolved using include patterns minus paths excluded by exclude
   *     patterns.
   */
  @Override
  public Set<String> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException {
    ImmutableSet<ForwardRelPath> includePaths =
        resolvePathsMatchingGlobPatterns(include, basePath, excludeDirectories);
    ImmutableList<UnixGlobPattern> excludePatterns =
        exclude.stream().map(UnixGlobPattern::parse).collect(ImmutableList.toImmutableList());
    HashMap<String, Pattern> patternCache = new HashMap<>();
    return includePaths.stream()
        .filter(
            p ->
                excludePatterns.stream()
                    .noneMatch(
                        pt -> {
                          return pt.matches(p, patternCache);
                        }))
        .map(ForwardRelPath::toString)
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Resolves provided list of glob patterns into a set of paths.
   *
   * @param patterns The glob patterns to resolve.
   * @param basePath The base path used when resolving glob patterns.
   * @param excludeDirectories Flag indicating whether directories should be excluded from result.
   * @return The set of paths corresponding to requested patterns.
   */
  private static ImmutableSet<ForwardRelPath> resolvePathsMatchingGlobPatterns(
      Collection<String> patterns, AbsPath basePath, boolean excludeDirectories)
      throws IOException {
    return UnixGlob.forPath(basePath).addPatterns(patterns)
        .setExcludeDirectories(excludeDirectories).glob().stream()
        .map(includePath -> ForwardRelPath.ofRelPath(basePath.relativize(includePath)))
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Factory method for creating {@link NativeGlobber} instances.
   *
   * @param basePath The base path relative to which paths matching glob patterns will be resolved.
   */
  public static NativeGlobber create(AbsPath basePath) {
    return new NativeGlobber(basePath);
  }

  /** Factory for {@link com.facebook.buck.skylark.io.GlobberFactory}. */
  public static class Factory implements GlobberFactory {
    private final AbsPath root;

    public Factory(AbsPath root) {
      this.root = root;
    }

    @Override
    public AbsPath getRoot() {
      return root;
    }

    @Override
    public Globber create(ForwardRelPath basePath) {
      return new NativeGlobber(root.resolve(basePath));
    }

    @Override
    public void close() {}
  }
}
