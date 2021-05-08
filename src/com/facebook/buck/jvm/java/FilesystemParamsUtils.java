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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.GlobPatternMatcher;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.google.common.collect.ImmutableSet;
import java.util.List;

/** Utilities method for {@link FilesystemParams} */
public class FilesystemParamsUtils {

  private FilesystemParamsUtils() {}

  /** Returns a set of {@link PathMatcher} that represents ignores. */
  public static ImmutableSet<PathMatcher> getIgnoredPaths(FilesystemParams filesystemParams) {
    List<String> ignoreGlobs = filesystemParams.getGlobIgnorePathsList();
    ImmutableSet.Builder<PathMatcher> ignores =
        ImmutableSet.builderWithExpectedSize(ignoreGlobs.size());
    for (String glob : ignoreGlobs) {
      ignores.add(GlobPatternMatcher.of(glob));
    }
    return ignores.build();
  }

  /** Creates {@link FilesystemParams} */
  public static FilesystemParams of(ProjectFilesystem projectFilesystem) {
    AbsPath rootPath = projectFilesystem.getRootPath();
    RelPath configuredBuckOut = projectFilesystem.getBuckPaths().getConfiguredBuckOut();

    FilesystemParams.Builder builder = FilesystemParams.newBuilder();
    builder.setRootPath(
        com.facebook.buck.javacd.model.AbsPath.newBuilder().setPath(rootPath.toString()).build());
    builder.setConfiguredBuckOut(
        com.facebook.buck.javacd.model.RelPath.newBuilder()
            .setPath(configuredBuckOut.toString())
            .build());
    for (PathMatcher pathMatcher : projectFilesystem.getIgnoredPaths()) {
      builder.addGlobIgnorePaths(pathMatcher.getGlob());
    }
    return builder.build();
  }
}
