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
import com.facebook.buck.rules.macros.BaseLocationMacro;

/** Base class for expanding {@link BaseLocationMacro}s to strings. */
abstract class AbstractLocationMacroTypeCoercer<T extends BaseLocationMacro>
    implements MacroTypeCoercer<T> {

  private final TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
      buildTargetWithOutputsTypeCoercer;

  public AbstractLocationMacroTypeCoercer(
      TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
          buildTargetWithOutputsTypeCoercer) {
    this.buildTargetWithOutputsTypeCoercer = buildTargetWithOutputsTypeCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return buildTargetWithOutputsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, T macro, TypeCoercer.Traversal traversal) {
    buildTargetWithOutputsTypeCoercer.traverse(cellRoots, macro.getTargetWithOutputs(), traversal);
  }

  protected BuildTargetWithOutputs coerceTarget(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      String arg)
      throws CoerceFailedException {
    return buildTargetWithOutputsTypeCoercer.coerceBoth(
        cellNameResolver,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfiguration,
        arg);
  }
}
