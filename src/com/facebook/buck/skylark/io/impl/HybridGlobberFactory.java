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
import com.facebook.buck.io.watchman.WatchRoot;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;

/** Provides instances of {@link com.facebook.buck.skylark.io.impl.HybridGlobber}. */
public class HybridGlobberFactory implements GlobberFactory {

  private final WatchmanClient watchmanClient;
  private final long queryPollTimeoutNanos;
  private final long queryWarnTimeoutNanos;
  private final AbsPath projectRoot;
  private final ProjectWatch projectWatch;

  private HybridGlobberFactory(
      WatchmanClient watchmanClient,
      long queryPollTimeoutNanos,
      long queryWarnTimeoutNanos,
      AbsPath projectRoot,
      ImmutableMap<AbsPath, ProjectWatch> projectWatches) {
    this.watchmanClient = watchmanClient;
    this.queryPollTimeoutNanos = queryPollTimeoutNanos;
    this.queryWarnTimeoutNanos = queryWarnTimeoutNanos;
    this.projectRoot = projectRoot;
    this.projectWatch = projectWatches.get(projectRoot);
    Preconditions.checkArgument(
        projectWatch != null,
        "project root %s must be in the set of project watches %s",
        projectRoot,
        projectWatches.keySet());
  }

  @Override
  public AbsPath getRoot() {
    return projectRoot;
  }

  @Override
  public Globber create(ForwardRelPath basePathRel) {
    final AbsPath basePath = projectRoot.resolve(basePathRel);
    WatchRoot watchRoot = projectWatch.getWatchRoot();
    return new HybridGlobber(
        NativeGlobber.create(basePath),
        WatchmanGlobber.create(
            watchmanClient,
            queryPollTimeoutNanos,
            queryWarnTimeoutNanos,
            projectWatch.getProjectPrefix().resolve(basePathRel),
            watchRoot));
  }

  @Override
  public void close() throws IOException {}

  public static HybridGlobberFactory using(Watchman watchman, AbsPath projectRoot) {
    return new HybridGlobberFactory(
        watchman.getPooledClient(),
        watchman.getQueryPollTimeoutNanos(),
        watchman.getQueryWarnTimeoutNanos(),
        projectRoot,
        watchman.getProjectWatches());
  }
}
