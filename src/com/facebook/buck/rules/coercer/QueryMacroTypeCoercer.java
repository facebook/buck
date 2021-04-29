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
import com.facebook.buck.rules.macros.QueryMacro;
import com.facebook.buck.rules.macros.UnconfiguredQueryMacro;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.UnconfiguredQuery;
import com.google.common.collect.ImmutableList;
import java.util.function.Function;

/** A type coercer for macros accepting a single {@link Query} arg. */
class QueryMacroTypeCoercer<U extends UnconfiguredQueryMacro, M extends QueryMacro>
    implements MacroTypeCoercer<U, M> {

  private final TypeCoercer<UnconfiguredQuery, Query> queryCoercer;
  private final Class<U> uClass;
  private final Class<M> mClass;
  private final Function<UnconfiguredQuery, U> factory;

  public QueryMacroTypeCoercer(
      TypeCoercer<UnconfiguredQuery, Query> queryCoercer,
      Class<U> uClass,
      Class<M> mClass,
      Function<UnconfiguredQuery, U> factory) {
    this.queryCoercer = queryCoercer;
    this.uClass = uClass;
    this.mClass = mClass;
    this.factory = factory;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return queryCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(CellNameResolver cellRoots, U macro, Traversal traversal) {
    queryCoercer.traverseUnconfigured(cellRoots, macro.getQuery(), traversal);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, M macro, Traversal traversal) {
    queryCoercer.traverse(cellRoots, macro.getQuery(), traversal);
  }

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
    if (args.size() != 1) {
      throw new CoerceFailedException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    return factory.apply(
        queryCoercer.coerceToUnconfigured(
            cellNameResolver, filesystem, pathRelativeToProjectRoot, args.get(0)));
  }
}
