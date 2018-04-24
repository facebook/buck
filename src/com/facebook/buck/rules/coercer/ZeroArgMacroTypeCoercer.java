/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.TypeCoercer.Traversal;
import com.facebook.buck.rules.macros.Macro;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** A coercer for {@link Macro}s which take zero arguments. */
public class ZeroArgMacroTypeCoercer<M extends Macro> implements MacroTypeCoercer<M> {

  private final Class<M> mClass;
  private final M val;

  public ZeroArgMacroTypeCoercer(Class<M> mClass, M val) {
    this.mClass = mClass;
    this.val = val;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return false;
  }

  @Override
  public void traverse(CellPathResolver cellRoots, M macro, Traversal traversal) {}

  @Override
  public Class<M> getOutputClass() {
    return mClass;
  }

  @Override
  public M coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    if (!args.isEmpty()) {
      throw new CoerceFailedException(
          String.format("expected zero arguments (found %d)", args.size()));
    }
    return val;
  }
}
