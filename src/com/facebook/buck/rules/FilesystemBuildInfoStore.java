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
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class FilesystemBuildInfoStore implements BuildInfoStore {
  private final ProjectFilesystem filesystem;

  public FilesystemBuildInfoStore(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  @Override
  public Optional<String> readMetadata(BuildTarget buildTarget, String key) {
    return filesystem.readFileIfItExists(pathToMetadata(buildTarget).resolve(key));
  }

  @Override
  public ImmutableMap<String, String> getAllMetadata(BuildTarget buildTarget) throws IOException {
    return filesystem
        .getDirectoryContents(pathToMetadata(buildTarget))
        .stream()
        .map(path -> path.getFileName().toString())
        .collect(
            ImmutableMap.toImmutableMap(key -> key, key -> readMetadata(buildTarget, key).get()));
  }

  @Override
  public void updateMetadata(BuildTarget buildTarget, Map<String, String> metadata)
      throws IOException {
    Path metadataPath = pathToMetadata(buildTarget);
    filesystem.mkdirs(metadataPath);

    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      filesystem.writeContentsToPath(entry.getValue(), metadataPath.resolve(entry.getKey()));
    }
  }

  @Override
  public void deleteMetadata(BuildTarget buildTarget) throws IOException {
    filesystem.deleteRecursivelyIfExists(pathToMetadata(buildTarget));
  }

  @Override
  public void close() {}

  private final Path pathToMetadata(BuildTarget target) {
    return BuildInfo.getPathToBuildMetadataDirectory(target, filesystem);
  }
}
