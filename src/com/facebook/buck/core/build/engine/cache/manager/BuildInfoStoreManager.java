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

import com.facebook.buck.core.build.engine.buildinfo.BuildInfoStore;
import com.facebook.buck.core.build.engine.buildinfo.SQLiteBuildInfoStore;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the lifetimes of all {@link BuildInfoStore}s used in the build. */
public class BuildInfoStoreManager implements AutoCloseable {
  private final ConcurrentHashMap<AbsPath, BuildInfoStore> buildInfoStores =
      new ConcurrentHashMap<>();

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
            return new SQLiteBuildInfoStore(filesystem);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
