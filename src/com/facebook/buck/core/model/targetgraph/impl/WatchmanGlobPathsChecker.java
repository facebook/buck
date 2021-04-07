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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.skylark.io.impl.WatchmanGlobber;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Checks that paths exist and throw an exception if at least one path doesn't exist. This path
 * checker is used to avoid triggering stat and Eden materialization. We can not 100% trust
 * watchman, so rely on file system on negative cases
 */
class WatchmanPathsChecker implements PathsChecker {

  private static final Logger LOG = Logger.get(WatchmanPathsChecker.class);
  private static final long WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);

  // Caches by filesystem root
  private final LoadingCache<AbsPath, Set<ForwardRelativePath>> pathsCache = initCache();
  private final LoadingCache<AbsPath, Set<ForwardRelativePath>> filePathsCache = initCache();
  private final LoadingCache<AbsPath, Set<ForwardRelativePath>> dirPathsCache = initCache();

  private final boolean useFallbackFileSystemPathsChecker;
  private final MissingPathsChecker fallbackFileSystemPathsChecker = new MissingPathsChecker();
  private final Watchman watchman;

  private static LoadingCache<AbsPath, Set<ForwardRelativePath>> initCache() {
    return CacheBuilder.newBuilder()
        .build(CacheLoader.from(rootPath -> Sets.newConcurrentHashSet()));
  }

  public WatchmanPathsChecker(Watchman watchman, boolean useFallbackFileSystemPathsChecker) {
    this.useFallbackFileSystemPathsChecker = useFallbackFileSystemPathsChecker;
    this.watchman = watchman;
  }

  public WatchmanPathsChecker(Watchman watchman) {
    this.useFallbackFileSystemPathsChecker = true;
    this.watchman = watchman;
  }

  @Override
  public void checkPaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableSet<ForwardRelativePath> paths) {
    checkPathsWithExtraCheckThenFallbackIfEnabled(
        projectFilesystem,
        buildTarget,
        paths,
        pathsCache,
        EnumSet.of(WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        () -> {
          fallbackFileSystemPathsChecker.checkPaths(projectFilesystem, buildTarget, paths);
        });
  }

  @Override
  public void checkFilePaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableSet<ForwardRelativePath> filePaths) {
    checkPathsWithExtraCheckThenFallbackIfEnabled(
        projectFilesystem,
        buildTarget,
        filePaths,
        filePathsCache,
        EnumSet.of(
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE,
            WatchmanGlobber.Option.EXCLUDE_DIRECTORIES),
        () -> {
          fallbackFileSystemPathsChecker.checkFilePaths(projectFilesystem, buildTarget, filePaths);
        });
  }

  @Override
  public void checkDirPaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableSet<ForwardRelativePath> dirPaths) {
    checkPathsWithExtraCheckThenFallbackIfEnabled(
        projectFilesystem,
        buildTarget,
        dirPaths,
        dirPathsCache,
        EnumSet.of(
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE,
            WatchmanGlobber.Option.EXCLUDE_REGULAR_FILES),
        () -> {
          fallbackFileSystemPathsChecker.checkDirPaths(projectFilesystem, buildTarget, dirPaths);
        });
  }

  @FunctionalInterface
  private interface FallbackPathChecker {
    void checkWithFallback();
  }

  private void checkPathsWithExtraCheckThenFallbackIfEnabled(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableSet<ForwardRelativePath> paths,
      LoadingCache<AbsPath, Set<ForwardRelativePath>> pathsCache,
      EnumSet<WatchmanGlobber.Option> options,
      FallbackPathChecker fallbackPathChecker) {
    try {
      checkPathsWithExtraCheck(buildTarget, projectFilesystem, paths, pathsCache, options);
    } catch (Exception e) {
      if (useFallbackFileSystemPathsChecker) {
        LOG.warn("Watchman could not find paths, fallback to file system for verification: " + e);
        fallbackPathChecker.checkWithFallback();
      } else {
        throw e;
      }
    }
  }

  private void checkPathsWithExtraCheck(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSet<ForwardRelativePath> paths,
      LoadingCache<AbsPath, Set<ForwardRelativePath>> pathsCache,
      EnumSet<WatchmanGlobber.Option> options) {
    if (paths.isEmpty()) {
      return;
    }

    Set<ForwardRelativePath> checkedPaths =
        pathsCache.getUnchecked(projectFilesystem.getRootPath());

    Set<String> uncheckedPaths = new HashSet<>();
    ProjectWatch watch = watchman.getProjectWatches().get(projectFilesystem.getRootPath());
    Path watchmanRootPath = Paths.get(watch.getWatchRoot());
    for (ForwardRelativePath relativePath : paths) {
      if (!checkedPaths.add(relativePath)) {
        continue;
      }
      Path path = relativePath.toPath(projectFilesystem.getFileSystem());
      Path watchmanPath = watchmanRootPath.relativize(projectFilesystem.resolve(path));
      // TODO: paths could be patterns, such as containing *, maybe add a checker.
      uncheckedPaths.add(watchmanPath.toString());
    }
    if (uncheckedPaths.isEmpty()) {
      return;
    }

    try {
      Optional<ImmutableSet<String>> watchmanGlob =
          glob(watchman, uncheckedPaths, projectFilesystem, options);
      if (!watchmanGlob.isPresent()) {
        throw new HumanReadableException(
            "%s references non-existing or incorrect type of file or directory '%s'",
            buildTarget, paths);
      }
      for (String path : uncheckedPaths) {
        if (!watchmanGlob.get().contains(path)) {
          throw new HumanReadableException(
              "%s references non-existing or incorrect type of file or directory '%s'",
              buildTarget, path);
        }
      }
    } catch (IOException e) {
      throw new HumanReadableException(
          e, "%s Watchman failed to query file or directory '%s'", buildTarget, paths);
    } catch (InterruptedException e) {
      throw new HumanReadableException(
          e, "%s Watchman is interrupted when querying file or directory '%s'", buildTarget, paths);
    }
  }

  private Optional<ImmutableSet<String>> glob(
      Watchman watchman,
      Collection<String> patterns,
      ProjectFilesystem projectFilesystem,
      EnumSet<WatchmanGlobber.Option> options)
      throws IOException, InterruptedException {
    if (patterns.isEmpty()) {
      return Optional.empty();
    }

    ProjectWatch watch = watchman.getProjectWatches().get(projectFilesystem.getRootPath());
    if (watch == null) {
      String msg =
          String.format(
              "Path [%s] is not watched. The list of watched project: [%s]",
              projectFilesystem.getRootPath(), watchman.getProjectWatches().keySet());
      throw new FileSystemNotWatchedException(msg);
    }

    WatchmanClient watchmanClient = watchman.getPooledClient();
    WatchmanGlobber globber = WatchmanGlobber.create(watchmanClient, "", watch.getWatchRoot());
    return globber.run(patterns, ImmutableList.of(), options, TIMEOUT_NANOS, WARN_TIMEOUT_NANOS);
  }
}
