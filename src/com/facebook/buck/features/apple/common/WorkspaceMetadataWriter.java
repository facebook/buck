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

package com.facebook.buck.features.apple.common;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;

/** Writes workspace metadata information to a json file. */
public class WorkspaceMetadataWriter {
  private final String projectVersion;
  private final ImmutableSet<BuildTarget> requiredBuildTargets;
  private final ImmutableSet<Path> xcconfigPaths;
  private final ImmutableList<CopyInXcode> filesToCopyInXcode;
  private final ProjectFilesystem projectFilesystem;

  public WorkspaceMetadataWriter(
      String projectVersion,
      ImmutableSet<BuildTarget> requiredBuildTargets,
      ImmutableSet<Path> xcconfigPaths,
      ImmutableList<CopyInXcode> filesToCopyInXcode,
      ProjectFilesystem projectFilesystem) {
    this.projectVersion = projectVersion;
    this.requiredBuildTargets = requiredBuildTargets;
    this.xcconfigPaths = xcconfigPaths;
    this.filesToCopyInXcode = filesToCopyInXcode;
    this.projectFilesystem = projectFilesystem;
  }

  /**
   * Writes the metadata information to the workspace at the input path.
   *
   * @param workspacePath The workspace path to write to.
   * @throws IOException
   */
  public void writeToWorkspaceAtPath(Path workspacePath) throws IOException {
    projectFilesystem.mkdirs(workspacePath);
    ImmutableList<String> requiredTargetsStrings =
        requiredBuildTargets.stream()
            .map(Object::toString)
            .sorted()
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Object> meta = ImmutableMap.of("project-version", projectVersion);
    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "meta",
            meta,
            "required-targets",
            requiredTargetsStrings,
            "xcconfig-paths",
            xcconfigPaths,
            "copy-in-xcode",
            filesToCopyInXcode);
    String jsonString = ObjectMappers.WRITER.writeValueAsString(data);
    projectFilesystem.writeContentsToPath(
        jsonString, workspacePath.resolve("buck-project.meta.json"));
  }
}
