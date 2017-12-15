/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.lib.vfs.UnixGlob.FilesystemCalls;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simple implementation of globbing functionality that allows resolving file paths based on
 * include patterns (file patterns that should be returned) minus exclude patterns (file patterns
 * that should be excluded from the resulting set).
 *
 * <p>Since this is a simple implementation it does not support caching and other smarts.
 */
public class SimpleGlobber implements Globber {

  private static final FilesystemCalls STRICT_EXISTENCE_FILESYSTEM_CALLS =
      new StrictExistenceFileSystemCalls();

  /** Path used as a root when resolving patterns. */
  private final Path basePath;

  private SimpleGlobber(Path basePath) {
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
    ImmutableSet<String> includePaths =
        resolvePathsMatchingGlobPatterns(include, basePath, excludeDirectories);
    ImmutableSet<String> excludePaths =
        resolvePathsMatchingGlobPatterns(exclude, basePath, excludeDirectories);
    return Sets.difference(includePaths, excludePaths);
  }

  /**
   * Resolves provided list of glob patterns into a set of paths.
   *
   * @param patterns The glob patterns to resolve.
   * @param basePath The base path used when resolving glob patterns.
   * @param excludeDirectories Flag indicating whether directories should be excluded from result.
   * @return The set of paths corresponding to requested patterns.
   */
  private static ImmutableSet<String> resolvePathsMatchingGlobPatterns(
      Collection<String> patterns, Path basePath, boolean excludeDirectories) throws IOException {
    return UnixGlob.forPath(basePath)
        .addPatterns(patterns)
        .setExcludeDirectories(excludeDirectories)
        // The default here silently suppresses FileNotFoundExceptions; this implementation doesn't.
        .setFilesystemCalls(new AtomicReference<>(STRICT_EXISTENCE_FILESYSTEM_CALLS))
        .glob()
        .stream()
        .map(includePath -> includePath.relativeTo(basePath).getPathString())
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Factory method for creating {@link SimpleGlobber} instances.
   *
   * @param basePath The base path relative to which paths matching glob patterns will be resolved.
   */
  public static Globber create(Path basePath) {
    return new SimpleGlobber(basePath);
  }

  /**
   * This class requires that all paths being checked must exist. This prevents silent failure, and
   * specifically is useful in the globber, where we want bad file paths to explicitly throw an
   * exception that can ultimately be shown to the user. Otherwise, a mistake like a typo could
   * cause files to be silently excluded, which is frustrating to debug.
   */
  private static class StrictExistenceFileSystemCalls implements FilesystemCalls {
    @Override
    public Collection<Dirent> readdir(Path path, Symlinks symlinks) throws IOException {
      return path.readdir(path.getFileSystem(), symlinks);
    }

    @Override
    public FileStatus statIfFound(Path path, Symlinks symlinks) throws IOException {
      if (!path.exists(path.getFileSystem(), symlinks)) {
        throw new FileNotFoundException(path.getPathString());
      }

      return path.statIfFound(path.getFileSystem(), symlinks);
    }
  }
}
