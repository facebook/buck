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

package com.facebook.buck.core.parser;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.Capability;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.io.watchman.WatchmanQueryTimedOutException;
import com.facebook.buck.skylark.io.impl.SyncCookieState;
import com.facebook.buck.skylark.io.impl.WatchmanGlobber;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Discover paths for packages which contain targets that match specification (build target pattern)
 *
 * <p>This computation uses <a href="https://facebook.github.io/watchman/">Watchman</a> for finding
 * build files. This computation fails if Watchman is unavailable.
 *
 * <p>This computation has no dependencies on other computations.
 *
 * <p>See {@link BuildTargetPatternToBuildPackagePathComputation} for an equivalent computation
 * which does not use Watchman.
 */
public class WatchmanBuildPackageComputation
    implements GraphComputation<BuildTargetPatternToBuildPackagePathKey, BuildPackagePaths> {
  private final String buildFileName;
  private final ProjectFilesystemView filesystemView;
  private final ProjectWatch watch;
  private final Watchman watchman;

  private static final Logger LOG = Logger.get(WatchmanBuildPackageComputation.class);

  /**
   * @param buildFileName Name of the build file to search for, for example 'BUCK'
   * @param filesystemView Cell to search in. Discovered paths are relative to {@link
   *     ProjectFilesystemView#getRootPath()}.
   * @throws FileSystemNotWatchedException {@code watchman} does not include a watch for {@link
   *     ProjectFilesystemView#getRootPath()}.
   */
  public WatchmanBuildPackageComputation(
      String buildFileName, ProjectFilesystemView filesystemView, Watchman watchman)
      throws FileSystemNotWatchedException {
    this.buildFileName = buildFileName;
    this.filesystemView = filesystemView;
    this.watchman = watchman;

    ProjectWatch watch = watchman.getProjectWatches().get(AbsPath.of(filesystemView.getRootPath()));
    if (watch == null) {
      throw new FileSystemNotWatchedException();
    }
    this.watch = watch;
  }

  @Override
  public ComputationIdentifier<BuildPackagePaths> getIdentifier() {
    return BuildTargetPatternToBuildPackagePathKey.IDENTIFIER;
  }

  @Override
  public BuildPackagePaths transform(
      BuildTargetPatternToBuildPackagePathKey key, ComputationEnvironment env)
      throws IOException, InterruptedException, WatchmanQueryTimedOutException {
    BuildTargetPattern targetPattern = key.getPattern();
    Path basePath =
        targetPattern
            .getCellRelativeBasePath()
            .getPath()
            .toPath(filesystemView.getRootPath().getFileSystem());
    try {
      LOG.info("Starting fetch of basepath %s", basePath);
      ImmutableSet<String> buildFiles = findBuildFiles(basePath, targetPattern.isRecursive());
      BuildPackagePaths ret = getPackagePathsOfNonIgnoredBuildFiles(basePath, buildFiles);
      LOG.info("Ending fetch of basepath %s, ret: %s", basePath, ret.getPackageRoots());
      return ret;
    } catch (WatchmanQueryFailedException e) {
      validateBasePath(basePath, e);
      throw e;
    } catch (FileSystemException e) {
      throw enrichFileSystemException(e);
    }
  }

  private ImmutableSet<String> findBuildFiles(Path basePath, boolean recursive)
      throws IOException, InterruptedException {
    LOG.info("Finding build files for %s, recursive: %s", basePath, recursive);
    String pattern = escapeGlobPattern(buildFileName);
    if (recursive) {
      pattern = "**/" + pattern;
    }
    return glob(basePath, pattern);
  }

  private ImmutableSet<String> glob(Path basePath, String pattern)
      throws IOException, InterruptedException {
    Optional<ImmutableSet<String>> paths;
    try (WatchmanClient watchmanClient = watchman.createClient()) {
      // TODO: Avoid costly per-glob sync cookies by reusing SyncCookieState instances.
      WatchmanGlobber globber =
          WatchmanGlobber.create(
              watchmanClient,
              new SyncCookieState(),
              getWatchRelativePath(basePath).toString(),
              watch.getWatchRoot());
      LOG.info("Globber with basepath %s, pattern: %s", basePath, pattern);

      paths =
          globber.run(
              ImmutableList.of(pattern),
              filesystemView.toWatchmanQuery(ImmutableSet.copyOf(Capability.values())).stream()
                  .filter(ps -> ps.startsWith(basePath.toString()))
                  .map(ps -> ps.substring(basePath.toString().length()))
                  .collect(ImmutableSet.toImmutableSet()),
              EnumSet.of(
                  WatchmanGlobber.Option.EXCLUDE_DIRECTORIES,
                  WatchmanGlobber.Option.FORCE_CASE_SENSITIVE));
      LOG.info("Globber with basepath %s, pattern: %s result: %s", basePath, pattern, paths);
    }
    if (!paths.isPresent()) {
      // TODO: If globber.run returns null, it will first write an error to the console claiming
      // that Watchman is being disabled. This isn't necessarily true; Watchman might still be used
      // in the future. We should silence the 'disabling Watchman' message to avoid confusion.
      throw new WatchmanQueryTimedOutException();
    }
    return paths.get();
  }

  /**
   * Get the full relative build packages for the given build files. Filter out any packages that
   * should be ignored
   */
  private BuildPackagePaths getPackagePathsOfNonIgnoredBuildFiles(
      Path basePath, ImmutableSet<String> buildFilePaths) {
    ImmutableSortedSet<Path> relativeBuildFiles =
        buildFilePaths.stream()
            .map((String buildFilePath) -> getPackagePathOfBuildFile(basePath, buildFilePath))
            .filter(p -> !filesystemView.isIgnored(p))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    return ImmutableBuildPackagePaths.of(relativeBuildFiles);
  }

  private Path getPackagePathOfBuildFile(Path basePath, String buildFilePath) {
    return MorePaths.getParentOrEmpty(basePath.resolve(buildFilePath));
  }

  /** @throws NotDirectoryException {@code basePath} does not exist or is not a directory. */
  private void validateBasePath(Path basePath, Exception cause) throws IOException {
    if (!filesystemView.isDirectory(basePath)) {
      IOException e = new NotDirectoryException(basePath.toString());
      e.initCause(cause);
      throw e;
    }
  }

  private FileSystemException enrichFileSystemException(FileSystemException exception) {
    if (!(exception instanceof NotDirectoryException) && isNotADirectoryError(exception)) {
      FileSystemException e = new NotDirectoryException(exception.getFile());
      e.initCause(exception);
      return e;
    }
    return exception;
  }

  private boolean isNotADirectoryError(FileSystemException exception) {
    String reason = exception.getReason();
    if (reason == null) {
      return false;
    }
    String posixMessage = "Not a directory";
    String windowsMessage = "The directory name is invalid";
    return reason.equals(posixMessage) || reason.contains(windowsMessage);
  }

  /**
   * Convert the given path into a path which Watchman will recognize.
   *
   * @param path A path relative to {@link WatchmanBuildPackageComputation#filesystemView}.
   * @return A path relative to {@code watch.getWatchRoot()}. Refers to the same file as the given
   *     path.
   * @throws IOException The given path does not exist.
   * @throws IllegalArgumentException The given path is not a descendant of {@code
   *     watch.getWatchRoot()}.
   */
  private Path getWatchRelativePath(Path path) throws IOException, IllegalArgumentException {
    Path realBasePath = filesystemView.resolve(path).toRealPath();
    return filesystemView.resolve(watch.getWatchRoot()).relativize(realBasePath);
  }

  private static String escapeGlobPattern(String pathComponent) {
    return pathComponent.replaceAll("[\\[*?]", "[$0]");
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildTargetPatternToBuildPackagePathKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildTargetPatternToBuildPackagePathKey key) {
    return ImmutableSet.of();
  }
}
