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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import java.nio.file.Path;

class DefaultOutputPathResolver implements OutputPathResolver {
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;

  DefaultOutputPathResolver(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    this.filesystem = projectFilesystem;
    this.buildTarget = buildTarget;
  }

  @Override
  public Path getTempPath() {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "%s");
  }

  @Override
  public Path resolvePath(OutputPath outputPath) {
    if (outputPath instanceof PublicOutputPath) {
      return outputPath.getPath();
    }
    return getRootPath().resolve(outputPath.getPath());
  }

  @Override
  public Path getRootPath() {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s");
  }
}
