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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.immutables.value.Value;

/** A specification used by the parser, via {@link TargetNodeSpec}, to match build files. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractBuildFileSpec {

  private static final Logger LOG = Logger.get(AbstractBuildFileSpec.class);
  private static final long WATCHMAN_QUERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

  // Base path where to find either a single build file or to recursively for many build files.
  @Value.Parameter
  abstract Path getBasePath();

  // If present, this indicates that the above path should be recursively searched for build files,
  // and that the paths enumerated here should be ignored.
  @Value.Parameter
  abstract boolean isRecursive();

  // The absolute cell path in which the build spec exists
  @Value.Parameter
  abstract Path getCellPath();

  public static BuildFileSpec fromRecursivePath(Path basePath, Path cellPath) {
    return BuildFileSpec.of(basePath, /* recursive */ true, cellPath);
  }

  public static BuildFileSpec fromPath(Path basePath, Path cellPath) {
    return BuildFileSpec.of(basePath, /* recursive */ false, cellPath);
  }

  public static BuildFileSpec fromBuildTarget(BuildTarget target) {
    return fromPath(target.getBasePath(), target.getCellPath());
  }

  /** Find all build in the given {@link ProjectFilesystem}, and pass each to the given callable. */
  public void forEachBuildFile(
      ProjectFilesystem filesystem,
      String buildFileName,
      ParserConfig.BuildFileSearchMethod buildFileSearchMethod,
      Watchman watchman,
      Consumer<Path> function)
      throws IOException, InterruptedException {

    // If non-recursive, we just want the build file in the target spec's given base dir.
    if (!isRecursive()) {
      function.accept(filesystem.resolve(getBasePath().resolve(buildFileName)));
      return;
    }

    LOG.debug("Finding build files for %s under %s...", getBasePath(), filesystem.getRootPath());

    long walkStartTimeNanos = System.nanoTime();

    // Otherwise, we need to do a recursive walk to find relevant build files.
    boolean tryWatchman =
        buildFileSearchMethod == ParserConfig.BuildFileSearchMethod.WATCHMAN
            && watchman.getTransportPath().isPresent()
            && watchman.getProjectWatches().containsKey(filesystem.getRootPath());
    boolean walkComplete = false;
    if (tryWatchman) {
      ProjectWatch projectWatch =
          Preconditions.checkNotNull(watchman.getProjectWatches().get(filesystem.getRootPath()));
      LOG.debug(
          "Searching for %s files (watch root %s, project prefix %s, base path %s) with Watchman",
          buildFileName,
          projectWatch.getWatchRoot(),
          projectWatch.getProjectPrefix(),
          getBasePath());
      try (WatchmanClient watchmanClient = watchman.createClient()) {
        walkComplete =
            forEachBuildFileWatchman(
                filesystem,
                watchmanClient,
                projectWatch.getWatchRoot(),
                projectWatch.getProjectPrefix(),
                getBasePath(),
                buildFileName,
                function);
      }
    } else {
      LOG.debug(
          "Not using Watchman (search method %s, socket path %s, root present %s)",
          buildFileSearchMethod,
          watchman.getTransportPath().isPresent(),
          watchman.getProjectWatches().containsKey(filesystem.getRootPath()));
    }

    if (!walkComplete) {
      LOG.debug(
          "Searching for %s files under %s using physical filesystem crawl (note: this is slow)",
          buildFileName, filesystem.getRootPath());
      forEachBuildFileFilesystem(filesystem, buildFileName, function);
    }

    long walkTimeNanos = System.nanoTime() - walkStartTimeNanos;
    LOG.debug("Completed search in %d ms.", TimeUnit.NANOSECONDS.toMillis(walkTimeNanos));
  }

  @SuppressWarnings("unchecked")
  private static boolean forEachBuildFileWatchman(
      ProjectFilesystem filesystem,
      WatchmanClient watchmanClient,
      String watchRoot,
      Optional<String> projectPrefix,
      Path basePath,
      String buildFileName,
      Consumer<Path> function)
      throws IOException, InterruptedException {

    List<Object> query = Lists.newArrayList("query", watchRoot);
    Map<String, Object> params = new LinkedHashMap<>();
    if (projectPrefix.isPresent()) {
      params.put("relative_root", projectPrefix.get());
    }

    // Get the current state of the filesystem instead of waiting for a fence.
    params.put("sync_timeout", 0);

    Optional<Path> relativeBasePath = filesystem.getPathRelativeToProjectRoot(basePath);
    Preconditions.checkState(relativeBasePath.isPresent());
    // This should be a relative path from watchRoot/projectPrefix.
    params.put("path", Lists.newArrayList(relativeBasePath.get().toString()));

    // We only care about the paths to each of the files.
    params.put("fields", Lists.newArrayList("name"));

    // Query all files matching `buildFileName` which are either regular files or symlinks.
    params.put(
        "expression",
        Lists.newArrayList(
            "allof",
            "exists",
            Lists.newArrayList("name", buildFileName),
            // Assume there are no symlinks to build files.
            Lists.newArrayList("type", "f")));

    // TODO(bhamiltoncx): Consider directly adding the white/blacklist paths and globs instead
    // of filtering afterwards.

    query.add(params);
    Optional<? extends Map<String, ? extends Object>> queryResponse =
        watchmanClient.queryWithTimeout(WATCHMAN_QUERY_TIMEOUT_NANOS, query.toArray());
    if (!queryResponse.isPresent()) {
      LOG.warn("Timed out after %d ns for Watchman query %s", WATCHMAN_QUERY_TIMEOUT_NANOS, query);
      return false;
    }

    Map<String, ? extends Object> response = queryResponse.get();
    String error = (String) response.get("error");
    if (error != null) {
      throw new IOException(String.format("Error from Watchman query %s: %s", query, error));
    }

    String warning = (String) response.get("warning");
    if (warning != null) {
      LOG.warn("Watchman warning from query %s: %s", query, warning);
    }

    List<String> files = (List<String>) Preconditions.checkNotNull(response.get("files"));
    LOG.verbose("Query %s -> files %s", query, files);

    for (String file : files) {
      Path relativePath = Paths.get(file);
      if (!isIgnored(filesystem, relativePath)) {
        // To avoid an extra stat() and realpath(), we assume we have no symlinks here
        // (since Watchman doesn't follow them anyway), and directly resolve the path
        // instead of using ProjectFilesystem.resolve().
        function.accept(filesystem.getRootPath().resolve(relativePath));
      }
    }

    return true;
  }

  private void forEachBuildFileFilesystem(
      ProjectFilesystem filesystem, String buildFileName, Consumer<Path> function)
      throws IOException {
    if (!filesystem.isDirectory(getBasePath())) {
      throw new HumanReadableException(
          "The folder %s could not be found.\n"
              + "Please check that you spelled the name of the buck target correctly.",
          getBasePath());
    }
    filesystem.walkRelativeFileTree(
        getBasePath(),
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // Skip sub-dirs that we should ignore.
            if (isIgnored(filesystem, dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (buildFileName.equals(file.getFileName().toString())
                && !isIgnored(filesystem, file)) {
              function.accept(filesystem.resolve(file));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            throw exc;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** @return paths to build files that this spec match in the given {@link ProjectFilesystem}. */
  public ImmutableSet<Path> findBuildFiles(
      Cell cell, ParserConfig.BuildFileSearchMethod buildFileSearchMethod)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<Path> buildFiles = ImmutableSet.builder();

    forEachBuildFile(
        cell.getFilesystem(),
        cell.getBuildFileName(),
        buildFileSearchMethod,
        cell.getWatchman(),
        buildFiles::add);

    return buildFiles.build();
  }

  static boolean isIgnored(ProjectFilesystem filesystem, Path path) {
    // Ignoring buck-out in addition to the blacklist paths
    return filesystem.isIgnored(path)
        || new PathOrGlobMatcher(filesystem.getBuckPaths().getBuckOut()).matches(path);
  }
}
