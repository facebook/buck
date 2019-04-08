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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;

public class BuildTargetTypeCoercer extends LeafTypeCoercer<BuildTarget> {

  private final TypeCoercer<UnconfiguredBuildTargetView> unconfiguredBuildTargetTypeCoercer;

  public BuildTargetTypeCoercer(
      TypeCoercer<UnconfiguredBuildTargetView> unconfiguredBuildTargetTypeCoercer) {
    this.unconfiguredBuildTargetTypeCoercer = unconfiguredBuildTargetTypeCoercer;
  }

  @Override
  public Class<BuildTarget> getOutputClass() {
    return BuildTarget.class;
  }

  @Override
  public BuildTarget coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem alsoUnused,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputClass());
    }

    return unconfiguredBuildTargetTypeCoercer
        .coerce(cellRoots, alsoUnused, pathRelativeToProjectRoot, targetConfiguration, object)
        .configure(targetConfiguration);
  }
}
