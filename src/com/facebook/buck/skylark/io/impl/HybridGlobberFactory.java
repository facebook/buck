/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Provides instances of {@link com.facebook.buck.skylark.io.impl.HybridGlobber}. */
public class HybridGlobberFactory implements GlobberFactory {
  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

  private final WatchmanClient watchmanClient;
  private final java.nio.file.Path projectRoot;
  private final ImmutableMap<java.nio.file.Path, ProjectWatch> projectWatches;
  private final SyncCookieState syncCookieState;

  private HybridGlobberFactory(
      WatchmanClient watchmanClient,
      SyncCookieState syncCookieState,
      java.nio.file.Path projectRoot,
      ImmutableMap<java.nio.file.Path, ProjectWatch> projectWatches) {
    this.watchmanClient = watchmanClient;
    this.syncCookieState = syncCookieState;
    this.projectRoot = projectRoot;
    this.projectWatches = projectWatches;
  }

  /** Keeps relevant results of watch-project operation */
  private static class WatchProjectResult {
    private final String watchRoot;
    private final String relativePath;

    WatchProjectResult(String watchRoot, String relativePath) {
      this.watchRoot = watchRoot;
      this.relativePath = relativePath;
    }
  }

  /**
   * Ask Watchman to get the final path for a file, following links (and ReparsePoints on Windows).
   *
   * @param filePath the path for which we want to get the final path.
   * @return the inal path from Watchman for {@code filePath}, if Watchman is possible to calculate
   *     and {@code Optional.empty()} otherwise.
   * @throws IOException
   * @throws InterruptedException
   */
  public Optional<WatchProjectResult> getWatchmanRelativizedFinalPath(Path filePath)
      throws IOException, InterruptedException {
    return watchmanClient
        .queryWithTimeout(TIMEOUT_NANOS, "watch-project", filePath.toString())
        .map(
            result -> {
              String watchRoot = (String) result.get("watch");
              String relativePath = (String) result.get("relative_path");
              return new WatchProjectResult(
                  Objects.requireNonNull(watchRoot), Objects.requireNonNull(relativePath));
            });
  }

  @Override
  public Globber create(Path basePath) {
    java.nio.file.Path cellPath = projectRoot.toAbsolutePath();
    String watchRoot = cellPath.toString();
    @Nullable ProjectWatch projectWatch = projectWatches.get(cellPath);
    if (projectWatch != null) {
      watchRoot = projectWatch.getWatchRoot();
    }
    String relativeRoot = null;
    try {
      relativeRoot = basePath.relativeTo(basePath.getFileSystem().getPath(watchRoot)).toString();
    } catch (IllegalArgumentException e) {
      if (Platform.detect() == Platform.WINDOWS) {
        // It is possible that on Windows we have a base root that is going through a different
        // drive (ReparsePoint-ed path).
        // In such case the basePath and watchRoot could point to different drives. Relativization
        // on different drives results in an exception.
        // In such case, ask Watchman to relativize the ReparsePoint path to the project root.
        // If exception is thrown while getting the final path from Watchman or relativizing the
        // path, report the original exception.
        try {
          Optional<WatchProjectResult> watchmanRelativizedPaths =
              getWatchmanRelativizedFinalPath(basePath);
          if (watchmanRelativizedPaths.isPresent()) {
            WatchProjectResult result = watchmanRelativizedPaths.get();
            watchRoot = result.watchRoot;
            relativeRoot = result.relativePath;
          }
        } catch (IOException | InterruptedException e1) {
          throw e;
        }
      }
    }
    return new HybridGlobber(
        NativeGlobber.create(basePath),
        WatchmanGlobber.create(watchmanClient, syncCookieState, relativeRoot, watchRoot));
  }

  public static HybridGlobberFactory using(
      WatchmanClient watchmanClient,
      SyncCookieState syncCookieState,
      java.nio.file.Path projectRoot,
      ImmutableMap<java.nio.file.Path, ProjectWatch> projectWatches) {
    return new HybridGlobberFactory(watchmanClient, syncCookieState, projectRoot, projectWatches);
  }
}
