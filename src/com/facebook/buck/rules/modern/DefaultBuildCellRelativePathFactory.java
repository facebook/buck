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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

public class DefaultBuildCellRelativePathFactory implements BuildCellRelativePathFactory {
  private final Path buildCellRootPath;
  private final ProjectFilesystem filesystem;
  private final Optional<OutputPathResolver> outputPathResolver;

  @VisibleForTesting
  public DefaultBuildCellRelativePathFactory(
      Path buildCellRootPath,
      ProjectFilesystem filesystem,
      Optional<OutputPathResolver> outputPathResolver) {
    this.buildCellRootPath = buildCellRootPath;
    this.filesystem = filesystem;
    this.outputPathResolver = outputPathResolver;
  }

  @Override
  public BuildCellRelativePath from(Path buildableRelativePath) {
    return BuildCellRelativePath.fromCellRelativePath(
        buildCellRootPath, filesystem, buildableRelativePath);
  }

  @Override
  public BuildCellRelativePath from(OutputPath outputPath) {
    Preconditions.checkState(
        outputPathResolver.isPresent(),
        "Cannot resolve OutputPath if constructed with an empty OutputPathResolver");
    return from(outputPathResolver.get().resolvePath(outputPath));
  }
}
