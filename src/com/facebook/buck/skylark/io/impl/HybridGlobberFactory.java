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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.Path;
import javax.annotation.Nullable;

/** Provides instances of {@link com.facebook.buck.skylark.io.impl.HybridGlobber}. */
public class HybridGlobberFactory implements GlobberFactory {

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

  @Override
  public Globber create(Path basePath) {
    java.nio.file.Path cellPath = projectRoot.toAbsolutePath();
    String watchRoot = cellPath.toString();
    @Nullable ProjectWatch projectWatch = projectWatches.get(cellPath);
    if (projectWatch != null) {
      watchRoot = projectWatch.getWatchRoot();
    }
    String relativeRoot =
        basePath.relativeTo(basePath.getFileSystem().getPath(watchRoot)).toString();
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
