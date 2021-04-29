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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.TypeCoercer.Traversal;
import com.facebook.buck.rules.macros.EnvMacro;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/** Coercer for <code>env</code> macros. */
public class EnvMacroTypeCoercer implements MacroTypeCoercer<EnvMacro, EnvMacro> {

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    for (Class<?> type : types) {
      if (type.isAssignableFrom(getOutputClass())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Class<EnvMacro> getUnconfiguredOutputClass() {
    return EnvMacro.class;
  }

  @Override
  public Class<EnvMacro> getOutputClass() {
    return EnvMacro.class;
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, EnvMacro macro, Traversal traversal) {
    traversal.traverse(macro.getVar());
  }

  @Override
  public void traverse(CellNameResolver cellRoots, EnvMacro macro, Traversal traversal) {
    traversal.traverse(macro.getVar());
  }

  @Override
  public EnvMacro coerceToUnconfigured(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    Preconditions.checkState(
        args.size() == 1, String.format("expected a single argument: %s", args));
    return EnvMacro.of(args.get(0));
  }
}
