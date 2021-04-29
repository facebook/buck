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
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacro;
import com.facebook.buck.rules.macros.UnconfiguredQueryTargetsAndOutputsMacro;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.UnconfiguredQuery;
import com.google.common.collect.ImmutableList;

/** A type coercer for the {@link QueryTargetsAndOutputsMacro} macro. */
class QueryTargetsAndOutputsMacroTypeCoercer
    implements MacroTypeCoercer<
        UnconfiguredQueryTargetsAndOutputsMacro, QueryTargetsAndOutputsMacro> {

  private final TypeCoercer<UnconfiguredQuery, Query> queryCoercer;

  public QueryTargetsAndOutputsMacroTypeCoercer(
      TypeCoercer<UnconfiguredQuery, Query> queryCoercer) {
    this.queryCoercer = queryCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return queryCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots,
      UnconfiguredQueryTargetsAndOutputsMacro macro,
      Traversal traversal) {
    queryCoercer.traverseUnconfigured(cellRoots, macro.getQuery(), traversal);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, QueryTargetsAndOutputsMacro macro, Traversal traversal) {
    queryCoercer.traverse(cellRoots, macro.getQuery(), traversal);
  }

  @Override
  public Class<UnconfiguredQueryTargetsAndOutputsMacro> getUnconfiguredOutputClass() {
    return UnconfiguredQueryTargetsAndOutputsMacro.class;
  }

  @Override
  public Class<QueryTargetsAndOutputsMacro> getOutputClass() {
    return QueryTargetsAndOutputsMacro.class;
  }

  @Override
  public UnconfiguredQueryTargetsAndOutputsMacro coerceToUnconfigured(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {
    String separator = " ";
    String query;
    if (args.size() == 2) {
      separator = args.get(0);
      query = args.get(1);
    } else if (args.size() == 1) {
      query = args.get(0);
    } else {
      throw new CoerceFailedException(
          "One quoted query expression is expected, or a separator and a query");
    }
    return UnconfiguredQueryTargetsAndOutputsMacro.of(
        separator,
        queryCoercer.coerceToUnconfigured(
            cellNameResolver, filesystem, pathRelativeToProjectRoot, query));
  }
}
