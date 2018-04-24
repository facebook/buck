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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.TypeCoercer.Traversal;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacro;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** A type coercer for the {@link QueryTargetsAndOutputsMacro} macro. */
class QueryTargetsAndOutputsMacroTypeCoercer
    implements MacroTypeCoercer<QueryTargetsAndOutputsMacro> {

  private final TypeCoercer<Query> queryCoercer;

  public QueryTargetsAndOutputsMacroTypeCoercer(TypeCoercer<Query> queryCoercer) {
    this.queryCoercer = queryCoercer;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return queryCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellPathResolver cellRoots, QueryTargetsAndOutputsMacro macro, Traversal traversal) {
    queryCoercer.traverse(cellRoots, macro.getQuery(), traversal);
  }

  @Override
  public Class<QueryTargetsAndOutputsMacro> getOutputClass() {
    return QueryTargetsAndOutputsMacro.class;
  }

  @Override
  public QueryTargetsAndOutputsMacro coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
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
    return QueryTargetsAndOutputsMacro.of(
        separator, queryCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, query));
  }
}
