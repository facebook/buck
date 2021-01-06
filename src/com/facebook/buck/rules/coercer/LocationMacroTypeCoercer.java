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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.LocationMacro;
import com.google.common.collect.ImmutableList;

/** Coerces `$(location ...)` macros into {@link LocationMacro}. */
class LocationMacroTypeCoercer extends AbstractLocationMacroTypeCoercer<LocationMacro> {

  public LocationMacroTypeCoercer(
      TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
          buildTargetTypeCoercer) {
    super(buildTargetTypeCoercer);
  }

  @Override
  public Class<LocationMacro> getOutputClass() {
    return LocationMacro.class;
  }

  @Override
  public LocationMacro coerce(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (args.size() != 1 || args.get(0).isEmpty()) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    BuildTargetWithOutputs targetWithOutputs =
        coerceTarget(
            cellNameResolver,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            args.get(0));
    return LocationMacro.of(targetWithOutputs);
  }
}
