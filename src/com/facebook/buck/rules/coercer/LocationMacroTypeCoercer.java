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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.macros.LocationMacro;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

class LocationMacroTypeCoercer implements MacroTypeCoercer<LocationMacro> {

  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer;

  public LocationMacroTypeCoercer(TypeCoercer<BuildTarget> buildTargetTypeCoercer) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return buildTargetTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellPathResolver cellRoots, LocationMacro macro, TypeCoercer.Traversal traversal) {
    buildTargetTypeCoercer.traverse(cellRoots, macro.getTarget(), traversal);
  }

  @Override
  public Class<LocationMacro> getOutputClass() {
    return LocationMacro.class;
  }

  @Override
  public LocationMacro coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (args.size() != 1) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    LocationMacro.SplitResult parts = LocationMacro.splitSupplementaryOutputPart(args.get(0));
    BuildTarget target =
        buildTargetTypeCoercer.coerce(
            cellRoots, filesystem, pathRelativeToProjectRoot, parts.target);
    return LocationMacro.of(target, parts.supplementaryOutput);
  }
}
