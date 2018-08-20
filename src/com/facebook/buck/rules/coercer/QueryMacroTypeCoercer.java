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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.TypeCoercer.Traversal;
import com.facebook.buck.rules.macros.QueryMacro;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.function.Function;

/** A type coercer for macros accepting a single {@link Query} arg. */
class QueryMacroTypeCoercer<M extends QueryMacro> implements MacroTypeCoercer<M> {

  private final TypeCoercer<Query> queryCoercer;
  private final Class<M> mClass;
  private final Function<Query, M> factory;

  public QueryMacroTypeCoercer(
      TypeCoercer<Query> queryCoercer, Class<M> mClass, Function<Query, M> factory) {
    this.queryCoercer = queryCoercer;
    this.mClass = mClass;
    this.factory = factory;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return queryCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, M macro, Traversal traversal) {
    queryCoercer.traverse(cellRoots, macro.getQuery(), traversal);
  }

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
    if (args.size() != 1) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    return factory.apply(
        queryCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, args.get(0)));
  }
}
