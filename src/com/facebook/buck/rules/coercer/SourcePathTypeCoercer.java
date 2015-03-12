/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;

import java.nio.file.Path;

public class SourcePathTypeCoercer extends LeafTypeCoercer<SourcePath> {
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;
  private final TypeCoercer<Path> pathTypeCoercer;

  SourcePathTypeCoercer(
      TypeCoercer<BuildTarget> buildTargetTypeCoercer,
      TypeCoercer<Path> pathTypeCoercer) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.pathTypeCoercer = pathTypeCoercer;
  }

  @Override
  public Class<SourcePath> getOutputClass() {
    return SourcePath.class;
  }

  @Override
  public SourcePath coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if ((object instanceof String) &&
        (((String) object).startsWith("//") ||
            ((String) object).startsWith(":") ||
            ((String) object).startsWith("@"))) {
      BuildTarget buildTarget =
          buildTargetTypeCoercer.coerce(
              buildTargetParser,
              filesystem,
              pathRelativeToProjectRoot,
              object);
      return new BuildTargetSourcePath(filesystem, buildTarget);
    } else {
      Path path = pathTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object);
      if (path.isAbsolute()) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            "SourcePath cannot contain an absolute path");
      }
      return new PathSourcePath(filesystem, path);
    }
  }
}
