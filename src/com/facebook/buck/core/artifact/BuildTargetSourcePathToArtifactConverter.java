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

package com.facebook.buck.core.artifact;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility for converting legacy {@link ExplicitBuildTargetSourcePath} from rules to new {@link
 * Artifact}s.
 */
public class BuildTargetSourcePathToArtifactConverter {

  private BuildTargetSourcePathToArtifactConverter() {}

  /**
   * @param sourcePath the {@link SourcePath} of the legacy target. This should be a {@link
   *     BuildTargetSourcePath} of either {@link ExplicitBuildTargetSourcePath} or {@link
   *     ForwardingBuildTargetSourcePath}.
   * @return a {@link BuildArtifact} that refers to the {@link SourcePath} of a legacy {@link
   *     com.facebook.buck.core.rules.BuildRule}.
   */
  public static BuildArtifact convert(ProjectFilesystem filesystem, SourcePath sourcePath) {
    if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      return convert(
          filesystem, ((ExplicitBuildTargetSourcePath) sourcePath).getTarget(), sourcePath);
    }
    if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
      return convert(
          filesystem,
          ((ForwardingBuildTargetSourcePath) sourcePath).getTarget(),
          ((ForwardingBuildTargetSourcePath) sourcePath).getDelegate());
    }
    throw new IllegalArgumentException(
        "Given SourcePath should be one of ExplicitBuildTargetSourcePath or ForwardingBuildTargetSourcePath");
  }

  private static BuildArtifact convert(
      ProjectFilesystem filesystem, BuildTarget target, SourcePath sourcePath) {
    if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      ExplicitBuildTargetSourcePath explicitBuildTargetSourcePath =
          (ExplicitBuildTargetSourcePath) sourcePath;

      Path genDir = filesystem.getBuckPaths().getGenDir();

      return ArtifactImpl.ofLegacy(
          target,
          genDir,
          Paths.get("."),
          genDir.relativize(explicitBuildTargetSourcePath.getResolvedPath()),
          explicitBuildTargetSourcePath.getTarget().equals(target)
              ? explicitBuildTargetSourcePath
              : ExplicitBuildTargetSourcePath.of(
                  target, explicitBuildTargetSourcePath.getResolvedPath()));
    }
    if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
      return convert(
          filesystem, target, ((ForwardingBuildTargetSourcePath) sourcePath).getDelegate());
    }

    throw new IllegalArgumentException(
        "Given SourcePath should be one of ExplicitBuildTargetSourcePath or ForwardingBuildTargetSourcePath");
  }
}
