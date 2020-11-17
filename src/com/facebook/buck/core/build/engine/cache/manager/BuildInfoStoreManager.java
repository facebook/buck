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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.core.build.engine.buildinfo.BuildInfoCache;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoStore;
import com.facebook.buck.core.build.engine.buildinfo.SQLiteBuildInfoStore;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the lifetimes of all {@link BuildInfoStore}s used in the build. */
public class BuildInfoStoreManager implements AutoCloseable {
  private final ConcurrentHashMap<AbsPath, BuildInfoStore> buildInfoStores =
      new ConcurrentHashMap<>();

  // In memory cache for build info to enable fast retrieval during the early phases of
  // a Buck build. Should be held by BuckGlobalState to persist it between different Buck
  // runs using the same daemon.
  private final Optional<BuildInfoCache> buildInfoCache;

  public BuildInfoStoreManager(Optional<BuildInfoCache> buildInfoCache) {
    this.buildInfoCache = buildInfoCache;
  }

  @Override
  public void close() {
    for (BuildInfoStore store : buildInfoStores.values()) {
      store.close();
    }
  }

  public BuildInfoStore get(ProjectFilesystem filesystem) {
    return buildInfoStores.computeIfAbsent(
        filesystem.getRootPath(),
        path -> {
          try {
            return new SQLiteBuildInfoStore(
                filesystem,
                buildInfoCache.map(buildInfoCache -> buildInfoCache.getMetaDataCache(path)));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
