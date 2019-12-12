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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;

public class SourcePathTypeCoercer extends LeafTypeCoercer<SourcePath> {
  private final TypeCoercer<BuildTargetWithOutputs> buildTargetWithOutputsTypeCoercer;
  private final TypeCoercer<Path> pathTypeCoercer;

  public SourcePathTypeCoercer(
      TypeCoercer<BuildTargetWithOutputs> buildTargetWithOutputsTypeCoercer,
      TypeCoercer<Path> pathTypeCoercer) {
    this.buildTargetWithOutputsTypeCoercer = buildTargetWithOutputsTypeCoercer;
    this.pathTypeCoercer = pathTypeCoercer;
  }

  @Override
  public Class<SourcePath> getOutputClass() {
    return SourcePath.class;
  }

  @Override
  public SourcePath coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if ((object instanceof String)
        && (((String) object).contains("//") || ((String) object).startsWith(":"))) {
      BuildTargetWithOutputs buildTargetWithOutputs =
          buildTargetWithOutputsTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object);
      return DefaultBuildTargetSourcePath.of(buildTargetWithOutputs);
    } else {
      Path path =
          pathTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object);
      if (path.isAbsolute()) {
        throw CoerceFailedException.simple(
            object, getOutputClass(), "SourcePath cannot contain an absolute path");
      }
      return PathSourcePath.of(filesystem, path);
    }
  }
}
