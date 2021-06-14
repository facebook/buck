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
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import javax.annotation.Nullable;

/** Provides instances of {@link com.facebook.buck.skylark.io.impl.HybridGlobber}. */
public class HybridGlobberFactory implements GlobberFactory {

  private final WatchmanClient watchmanClient;
  private final AbsPath projectRoot;
  private final ImmutableMap<AbsPath, ProjectWatch> projectWatches;

  private HybridGlobberFactory(
      WatchmanClient watchmanClient,
      AbsPath projectRoot,
      ImmutableMap<AbsPath, ProjectWatch> projectWatches) {
    this.watchmanClient = watchmanClient;
    this.projectRoot = projectRoot;
    this.projectWatches = projectWatches;
  }

  @Override
  public AbsPath getRoot() {
    return projectRoot;
  }

  @Override
  public Globber create(ForwardRelPath basePathRel) {
    final AbsPath basePath = projectRoot.resolve(basePathRel);
    final AbsPath cellPath = projectRoot;
    String watchRoot = cellPath.toString();
    @Nullable ProjectWatch projectWatch = projectWatches.get(cellPath);
    if (projectWatch != null) {
      watchRoot = projectWatch.getWatchRoot();
    }
    String relativeRoot = basePathRel.toString();
    return new HybridGlobber(
        NativeGlobber.create(basePath),
        WatchmanGlobber.create(watchmanClient, relativeRoot, watchRoot));
  }

  @Override
  public void close() throws IOException {}

  public static HybridGlobberFactory using(Watchman watchman, AbsPath projectRoot) {
    return new HybridGlobberFactory(
        watchman.getPooledClient(), projectRoot, watchman.getProjectWatches());
  }
}
