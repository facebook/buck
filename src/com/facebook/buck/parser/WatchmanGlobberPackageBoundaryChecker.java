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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.skylark.io.impl.SyncCookieState;
import com.facebook.buck.skylark.io.impl.WatchmanGlobber;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
 * Similar to {@link ThrowingPackageBoundaryChecker}, a {@link PackageBoundaryChecker}
 * implementation that throws an exception if any file in a set does not belong to the same package
 * as provided build target only if cell configuration allows that, otherwise noop. This package
 * boundary checker uses watchman query to avoid file system stat, and the existence of the path
 * which should be checked by {@link com.facebook.buck.core.model.targetgraph.impl.PathsChecker}
 */
public class WatchmanGlobberPackageBoundaryChecker implements PackageBoundaryChecker {

  private static final Logger LOG = Logger.get(WatchmanGlobberPackageBoundaryChecker.class);
  private static final long WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

  private final Watchman watchman;
  private final Optional<PackageBoundaryChecker> fallbackPackageBoundaryChecker;
  private final SyncCookieState syncCookieState = new SyncCookieState();
  private final LoadingCache<Pair<Cell, ForwardRelativePath>, Optional<ForwardRelativePath>>
      basePathOfAncestorCache =
          CacheBuilder.newBuilder()
              .weakValues()
              .build(
                  new CacheLoader<
                      Pair<Cell, ForwardRelativePath>, Optional<ForwardRelativePath>>() {
                    @Override
                    public Optional<ForwardRelativePath> load(
                        Pair<Cell, ForwardRelativePath> cellPathPair) throws Exception {
                      ForwardRelativePath buildFileName =
                          ForwardRelativePath.of(
                              cellPathPair
                                  .getFirst()
                                  .getBuckConfigView(ParserConfig.class)
                                  .getBuildFileName());
                      ForwardRelativePath folderPath = cellPathPair.getSecond();
                      ForwardRelativePath buildFileCandidate = folderPath.resolve(buildFileName);
                      // This will stat the build file for EdenFS, but should be fine since
                      // we need to materialize build files anyway
                      if (cellPathPair.getFirst().getFilesystem().isFile(buildFileCandidate)) {
                        return Optional.of(folderPath);
                      }

                      if (folderPath.equals(ForwardRelativePath.EMPTY)) {
                        return Optional.empty();
                      }

                      // traverse up
                      ForwardRelativePath parent = folderPath.getParent();
                      if (parent == null) {
                        parent = ForwardRelativePath.EMPTY;
                      }

                      return basePathOfAncestorCache.get(
                          new Pair<>(cellPathPair.getFirst(), parent));
                    }
                  });

  public WatchmanGlobberPackageBoundaryChecker(
      Watchman watchman, PackageBoundaryChecker fallbackPackageBoundaryChecker) {
    this.watchman = watchman;
    this.fallbackPackageBoundaryChecker = Optional.of(fallbackPackageBoundaryChecker);
  }

  @VisibleForTesting
  WatchmanGlobberPackageBoundaryChecker(Watchman watchman) {
    this.watchman = watchman;
    this.fallbackPackageBoundaryChecker = Optional.empty();
  }

  @Override
  public void enforceBuckPackageBoundaries(
      Cell targetCell, BuildTarget target, ImmutableSet<ForwardRelativePath> paths) {

    ForwardRelativePath basePath = target.getCellRelativeBasePath().getPath();

    ParserConfig.PackageBoundaryEnforcement enforcing =
        targetCell
            .getBuckConfigView(ParserConfig.class)
            .getPackageBoundaryEnforcementPolicy(
                basePath.toPath(targetCell.getFilesystem().getFileSystem()));
    if (enforcing == ParserConfig.PackageBoundaryEnforcement.DISABLED) {
      return;
    }

    ImmutableSet<ForwardRelativePath> folderPaths;
    try {
      folderPaths = filterFolderPaths(paths, targetCell.getFilesystem());
    } catch (Exception e) {
      String formatString = "Failed to query watchman for target %s: %s";
      warnOrError(
          enforcing,
          targetCell,
          target,
          paths,
          fallbackPackageBoundaryChecker,
          formatString,
          target,
          e);
      return;
    }

    boolean isBasePathEmpty = basePath.isEmpty();

    for (ForwardRelativePath path : paths) {
      if (!isBasePathEmpty && !path.startsWith(basePath)) {
        String formatString = "'%s' in '%s' refers to a parent directory.";
        warnOrError(
            enforcing,
            targetCell,
            target,
            paths,
            fallbackPackageBoundaryChecker,
            formatString,
            basePath
                .toPath(targetCell.getFilesystem().getFileSystem())
                .relativize(path.toPath(targetCell.getFilesystem().getFileSystem())),
            target);
        continue;
      }

      Optional<ForwardRelativePath> ancestor =
          getBasePathOfAncestorTarget(path, targetCell, folderPaths);

      if (!ancestor.isPresent()) {
        warnOrError(
            enforcing,
            targetCell,
            target,
            paths,
            fallbackPackageBoundaryChecker,
            "Target '%s' refers to file '%s', which doesn't belong to any package. "
                + "More info at:\nhttps://buck.build/about/overview.html\n",
            target,
            path);
      }

      if (!ancestor.get().equals(basePath)) {
        ForwardRelativePath buildFileName =
            ForwardRelativePath.of(
                targetCell.getBuckConfigView(ParserConfig.class).getBuildFileName());
        ForwardRelativePath buckFile = ancestor.get().resolve(buildFileName);
        String formatString =
            "The target '%1$s' tried to reference '%2$s'.\n"
                + "This is not allowed because '%2$s' can only be referenced from '%3$s' \n"
                + "which is its closest parent '%4$s' file.\n"
                + "\n"
                + "You should find or create a rule in '%3$s' that references\n"
                + "'%2$s' and use that in '%1$s'\n"
                + "instead of directly referencing '%2$s'.\n"
                + "More info at:\nhttps://buck.build/concept/build_rule.html\n"
                + "\n"
                + "This issue might also be caused by a bug in buckd's caching.\n"
                + "Please check whether using `buck kill` resolves it.";

        warnOrError(
            enforcing,
            targetCell,
            target,
            paths,
            fallbackPackageBoundaryChecker,
            formatString,
            target,
            path,
            buckFile,
            buildFileName);
      }
    }
  }

  /**
   * return the nearest folder that contains build file for a path. This assume the exists of files
   * (which should have been checked by {@link
   * com.facebook.buck.core.model.targetgraph.impl.PathsChecker}
   *
   * @param path input path to find the nearest folder contains build files
   * @param cell the cell of input path
   * @param folderPaths paths that known to be folders, paths not in the set were considered as
   *     files or symlinks.
   * @return optional base path of ancestor of a target
   */
  private Optional<ForwardRelativePath> getBasePathOfAncestorTarget(
      ForwardRelativePath path, Cell cell, ImmutableSet<ForwardRelativePath> folderPaths) {

    ForwardRelativePath folderPath = folderPaths.contains(path) ? path : path.getParent();
    if (folderPath == null) {
      folderPath = ForwardRelativePath.EMPTY;
    }
    return basePathOfAncestorCache.getUnchecked(new Pair<>(cell, folderPath));
  }

  /**
   * Return paths that are folders from the input paths
   *
   * @param paths paths that to be filtered
   * @param projectFilesystem project file system for the input path
   * @return a set of folder paths out of input path
   */
  private ImmutableSet<ForwardRelativePath> filterFolderPaths(
      Set<ForwardRelativePath> paths, ProjectFilesystem projectFilesystem)
      throws IOException, InterruptedException {
    ProjectWatch watch = watchman.getProjectWatches().get(projectFilesystem.getRootPath());
    Path watchmanRootPath = Paths.get(watch.getWatchRoot());

    Set<String> watchmanPaths = new HashSet<>();
    for (ForwardRelativePath path : paths) {
      Path actualPath = path.toPath(projectFilesystem.getFileSystem());
      Path watchmanPath = watchmanRootPath.relativize(projectFilesystem.resolve(actualPath));
      watchmanPaths.add(watchmanPath.toString());
    }
    Optional<ImmutableSet<String>> watchmanGlob =
        glob(
            watchman,
            watchmanPaths,
            projectFilesystem,
            EnumSet.of(
                WatchmanGlobber.Option.FORCE_CASE_SENSITIVE,
                WatchmanGlobber.Option.EXCLUDE_REGULAR_FILES));
    if (!watchmanGlob.isPresent()) {
      return ImmutableSet.of();
    } else {
      return watchmanGlob.get().stream()
          .map(pathString -> ForwardRelativePath.of(pathString))
          .collect(ImmutableSet.toImmutableSet());
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

    try (WatchmanClient watchmanClient = watchman.createClient()) {
      ProjectWatch watch = watchman.getProjectWatches().get(projectFilesystem.getRootPath());
      if (watch == null) {
        throw new FileSystemNotWatchedException();
      }

      WatchmanGlobber globber =
          WatchmanGlobber.create(watchmanClient, syncCookieState, "", watch.getWatchRoot());
      return globber.run(patterns, ImmutableList.of(), options, TIMEOUT_NANOS, WARN_TIMEOUT_NANOS);
    }
  }

  private static void warnOrError(
      ParserConfig.PackageBoundaryEnforcement enforcing,
      Cell cell,
      BuildTarget target,
      ImmutableSet<ForwardRelativePath> paths,
      Optional<PackageBoundaryChecker> fallbackPackageBoundaryChecker,
      String formatString,
      Object... formatArgs) {
    if (enforcing == ParserConfig.PackageBoundaryEnforcement.ENFORCE) {
      if (fallbackPackageBoundaryChecker.isPresent()) {
        LOG.warn(
            "Did not pass the watchman-based package boundary checker, fallback to filesystem-based package boundary checker: "
                + formatString,
            formatArgs);
        fallbackPackageBoundaryChecker.get().enforceBuckPackageBoundaries(cell, target, paths);
      } else {
        throw new HumanReadableException(formatString, formatArgs);
      }
    } else {
      LOG.warn(formatString, formatArgs);
    }
  }
}
