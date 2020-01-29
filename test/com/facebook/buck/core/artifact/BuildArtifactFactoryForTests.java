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
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;

public class BuildArtifactFactoryForTests {

  protected final BuildTarget target;
  private final Path basePath;
  private final Path genDir;

  public BuildArtifactFactoryForTests(BuildTarget target, ProjectFilesystem filesystem) {
    this.target = target;
    this.genDir = filesystem.getBuckPaths().getGenDir();
    this.basePath = BuildPaths.getBaseDir(filesystem, target).toPath(filesystem.getFileSystem());
  }

  public DeclaredArtifact createDeclaredArtifact(Path output, Location location)
      throws ArtifactDeclarationException {
    return ArtifactImpl.of(target, genDir, basePath, output, location);
  }

  public BuildArtifact createBuildArtifact(Path output, Location location) {
    ArtifactImpl declared = ArtifactImpl.of(target, genDir, basePath, output, location);
    return declared.materialize(ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a")));
  }
}
