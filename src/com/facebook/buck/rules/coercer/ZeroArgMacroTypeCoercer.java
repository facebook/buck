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
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.UnconfiguredMacro;
import com.google.common.collect.ImmutableList;

/** A coercer for {@link Macro}s which take zero arguments. */
public class ZeroArgMacroTypeCoercer<U extends UnconfiguredMacro, M extends Macro>
    implements MacroTypeCoercer<U, M> {

  private final Class<U> uClass;
  private final Class<M> mClass;
  private final U unconfigured;

  public ZeroArgMacroTypeCoercer(Class<U> uClass, Class<M> mClass, U unconfigured) {
    this.uClass = uClass;
    this.mClass = mClass;
    this.unconfigured = unconfigured;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return false;
  }

  @Override
  public void traverseUnconfigured(CellNameResolver cellRoots, U macro, Traversal traversal) {}

  @Override
  public void traverse(CellNameResolver cellRoots, M macro, Traversal traversal) {}

  @Override
  public Class<U> getUnconfiguredOutputClass() {
    return uClass;
  }

  @Override
  public Class<M> getOutputClass() {
    return mClass;
  }

  @Override
  public U coerceToUnconfigured(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (!args.isEmpty()) {
      throw new CoerceFailedException(
          String.format("expected zero arguments (found %d)", args.size()));
    }
    return unconfigured;
  }
}
