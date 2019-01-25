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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

  public static BuildFileSpec fromUnconfiguredBuildTarget(UnconfiguredBuildTarget target) {
    return fromPath(target.getBasePath(), target.getCellPath());
  }

  @SuppressWarnings("unchecked")
  private static ImmutableSet<Path> findBuildFilesUsingWatchman(
      ProjectFilesystemView filesystemView,
      WatchmanClient watchmanClient,
      String watchRoot,
      Optional<String> projectPrefix,
      Path basePath,
      String buildFileName,
      ImmutableSet<PathMatcher> ignoredPaths)
      throws IOException, InterruptedException {

    List<Object> query = Lists.newArrayList("query", watchRoot);
    Map<String, Object> params = new LinkedHashMap<>();
    if (projectPrefix.isPresent()) {
      params.put("relative_root", projectPrefix.get());
    }

    // Get the current state of the filesystem instead of waiting for a fence.
    params.put("sync_timeout", 0);

    Path relativeBasePath;
    if (basePath.isAbsolute()) {
      Preconditions.checkState(filesystemView.isSubdirOf(basePath));
      relativeBasePath = filesystemView.relativize(basePath);
    } else {
      relativeBasePath = basePath;
    }
    // This should be a relative path from watchRoot/projectPrefix.
    params.put("path", Lists.newArrayList(relativeBasePath.toString()));

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
      throw new IOException(
          "Timed out after " + WATCHMAN_QUERY_TIMEOUT_NANOS + " ns for Watchman query " + query);
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

    List<String> files = (List<String>) Objects.requireNonNull(response.get("files"));
    LOG.verbose("Query %s -> files %s", query, files);

    ImmutableSet.Builder<Path> builder = ImmutableSet.builderWithExpectedSize(files.size());

    for (String file : files) {
      Path relativePath = Paths.get(file);

      if (!filesystemView.isIgnored(relativePath)
          && !matchesIgnoredPath(relativePath, ignoredPaths)) {
        // To avoid an extra stat() and realpath(), we assume we have no symlinks here
        // (since Watchman doesn't follow them anyway), and directly resolve the path
        // instead of using ProjectFilesystem.resolve().
        builder.add(filesystemView.resolve(relativePath));
      }
    }

    return builder.build();
  }

  private static boolean matchesIgnoredPath(
      Path relativePath, ImmutableSet<PathMatcher> ignoredPaths) {
    for (PathMatcher matcher : ignoredPaths) {
      if (matcher.matches(relativePath)) {
        return true;
      }
    }
    return false;
  }

  private ImmutableSet<Path> findBuildFilesUsingFilesystemCrawl(
      ProjectFilesystemView filesystemView,
      String buildFileName,
      ImmutableSet<PathMatcher> ignoredPaths)
      throws IOException {
    if (!filesystemView.isDirectory(getBasePath())) {
      throw new HumanReadableException(
          "The folder %s could not be found.\n"
              + "Please check that you spelled the name of the buck target correctly.",
          getBasePath());
    }

    Path buildFile = Paths.get(buildFileName);
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    filesystemView.walkFileTree(
        getBasePath(),
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // Filter ignoredPaths here in preVisitDirectory
            // It is faster than filtering with ProjectFileSystemView, because the latter also
            // applies filtering PathMatchers to all files, which is unnecessary
            Path relativePath = filesystemView.relativize(dir);
            if (matchesIgnoredPath(relativePath, ignoredPaths)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.getFileName().equals(buildFile)
                && !matchesIgnoredPath(filesystemView.relativize(file), ignoredPaths)) {
              builder.add(filesystemView.resolve(file));
              // TODO(buck_team): switch to custom FileVisitResult so we can skip sibling files
              // but not sibling dirs. This would save some time checking other files in the same
              // dir to be build files
              // return FileVisitResult.SKIP_SIBLING_FILES;
            }
            return FileVisitResult.CONTINUE;
          }
        });

    return builder.build();
  }

  /** @return paths to build files that this spec match in the given {@link ProjectFilesystem}. */
  public ImmutableSet<Path> findBuildFiles(
      String buildFileName,
      ProjectFilesystemView filesystemView,
      Watchman watchman,
      ParserConfig.BuildFileSearchMethod buildFileSearchMethod,
      ImmutableSet<PathMatcher> ignoredPaths)
      throws IOException, InterruptedException {

    // If non-recursive, we just want the build file in the target spec's given base dir.
    if (!isRecursive()) {
      Path buildFile = filesystemView.resolve(getBasePath().resolve(buildFileName));
      return ImmutableSet.of(buildFile);
    }

    LOG.debug(
        "Finding build files for %s under %s...", getBasePath(), filesystemView.getRootPath());

    long walkStartTimeNanos = System.nanoTime();
    try {
      // Otherwise, we need to do a recursive walk to find relevant build files.
      boolean tryWatchman =
          buildFileSearchMethod == ParserConfig.BuildFileSearchMethod.WATCHMAN
              && watchman.getTransportPath().isPresent()
              && watchman.getProjectWatches().containsKey(filesystemView.getRootPath());

      if (tryWatchman) {
        ProjectWatch projectWatch =
            Objects.requireNonNull(watchman.getProjectWatches().get(filesystemView.getRootPath()));
        LOG.debug(
            "Searching for %s files (watch root %s, project prefix %s, base path %s) with Watchman",
            buildFileName,
            projectWatch.getWatchRoot(),
            projectWatch.getProjectPrefix(),
            getBasePath());
        try (WatchmanClient watchmanClient = watchman.createClient()) {
          return findBuildFilesUsingWatchman(
              filesystemView,
              watchmanClient,
              projectWatch.getWatchRoot(),
              projectWatch.getProjectPrefix(),
              getBasePath(),
              buildFileName,
              ignoredPaths);
        } catch (IOException ex) {
          // Watchman failed, warn and fallback to filesystem crawl
          LOG.warn(
              ex, "Watchman failed to search for build files, falling back to filesystem crawl");
        }
      } else {
        LOG.debug(
            "Not using Watchman (search method %s, socket path %s, root present %s)",
            buildFileSearchMethod,
            watchman.getTransportPath().isPresent(),
            watchman.getProjectWatches().containsKey(filesystemView.getRootPath()));
      }

      LOG.debug(
          "Searching for %s files under %s using physical filesystem crawl (note: this is slow)",
          buildFileName, filesystemView.getRootPath());

      return findBuildFilesUsingFilesystemCrawl(filesystemView, buildFileName, ignoredPaths);

    } finally {
      long walkTimeNanos = System.nanoTime() - walkStartTimeNanos;
      LOG.debug("Completed search in %d ms.", TimeUnit.NANOSECONDS.toMillis(walkTimeNanos));
    }
  }
}
