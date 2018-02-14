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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class ResourcesExoHelper {
  @VisibleForTesting public static final Path RESOURCES_DIR = Paths.get("resources");

  private final SourcePathResolver pathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final ExopackageInfo.ResourcesInfo resourcesInfo;

  ResourcesExoHelper(
      SourcePathResolver pathResolver,
      ProjectFilesystem projectFilesystem,
      ExopackageInfo.ResourcesInfo resourcesInfo) {
    this.pathResolver = pathResolver;
    this.projectFilesystem = projectFilesystem;
    this.resourcesInfo = resourcesInfo;
  }

  public static ImmutableMap<Path, Path> getFilesToInstall(ImmutableMap<String, Path> filesByHash) {
    return ExopackageUtil.applyFilenameFormat(filesByHash, RESOURCES_DIR, "%s.apk");
  }

  /** Returns a map of hash to path for resource files. */
  public static ImmutableMap<String, Path> getResourceFilesByHash(
      SourcePathResolver pathResolver,
      ProjectFilesystem projectFilesystem,
      Stream<ExopackagePathAndHash> resourcesPaths) {
    return resourcesPaths.collect(
        ImmutableMap.toImmutableMap(
            pathAndHash ->
                projectFilesystem
                    .readFileIfItExists(pathResolver.getAbsolutePath(pathAndHash.getHashPath()))
                    .get(),
            i -> projectFilesystem.resolve(pathResolver.getAbsolutePath(i.getPath()))));
  }

  public ImmutableMap<Path, Path> getFilesToInstall() {
    return getFilesToInstall(getResourceFilesByHash());
  }

  public ImmutableMap<Path, String> getMetadataToInstall() {
    return ImmutableMap.of(
        RESOURCES_DIR.resolve("metadata.txt"),
        getResourceMetadataContents(getResourceFilesByHash()));
  }

  private ImmutableMap<String, Path> getResourceFilesByHash() {
    return getResourceFilesByHash(
        pathResolver, projectFilesystem, resourcesInfo.getResourcesPaths().stream());
  }

  private String getResourceMetadataContents(ImmutableMap<String, Path> filesByHash) {
    return Joiner.on("\n")
        .join(RichStream.from(filesByHash.keySet()).map(h -> "resources " + h).toOnceIterable());
  }
}
