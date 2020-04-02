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
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.BaseLocationMacro;
import com.facebook.buck.rules.macros.UnconfiguredBuildTargetMacro;

/** Base class for expanding {@link BaseLocationMacro}s to strings. */
abstract class AbstractLocationMacroTypeCoercer<
        U extends UnconfiguredBuildTargetMacro, T extends BaseLocationMacro>
    implements MacroTypeCoercer<U, T> {

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

  protected UnconfiguredBuildTargetWithOutputs coerceTarget(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      String arg)
      throws CoerceFailedException {
    return buildTargetWithOutputsTypeCoercer.coerceToUnconfigured(
        cellNameResolver, filesystem, pathRelativeToProjectRoot, arg);
  }
}
