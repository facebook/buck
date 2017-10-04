/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the lifetimes of all {@link BuildInfoStore}s used in the build. */
public class BuildInfoStoreManager implements AutoCloseable {
  private final ConcurrentHashMap<Path, BuildInfoStore> buildInfoStores = new ConcurrentHashMap<>();

  @Override
  public void close() {
    for (BuildInfoStore store : buildInfoStores.values()) {
      store.close();
    }
  }

  public BuildInfoStore get(
      ProjectFilesystem filesystem, CachingBuildEngine.MetadataStorage metadataStorage) {
    return buildInfoStores.computeIfAbsent(
        filesystem.getRootPath(),
        path -> {
          try {
            switch (getMetadataStorage(filesystem, metadataStorage)) {
              case SQLITE:
                return new SQLiteBuildInfoStore(filesystem);
              case FILESYSTEM:
                return new FilesystemBuildInfoStore(filesystem);
              default:
                throw new IllegalStateException();
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static CachingBuildEngine.MetadataStorage getMetadataStorage(
      ProjectFilesystem filesystem, CachingBuildEngine.MetadataStorage metadataStorage) {
    Path metadataPath = BuildInfoStore.getMetadataTypePath(filesystem);
    Optional<String> metadataString = filesystem.readFileIfItExists(metadataPath);
    // If we haven't written metadata.type at this point, we must be in a fresh directory.  Use the
    // default for this build engine.
    return metadataString.isPresent()
        ? CachingBuildEngine.MetadataStorage.valueOf(metadataString.get())
        : metadataStorage;
  }
}
