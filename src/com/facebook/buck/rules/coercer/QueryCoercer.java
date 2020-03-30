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
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.util.Optional;
import java.util.stream.Stream;

/** Coercer for {@link Query}s. */
public class QueryCoercer implements TypeCoercer<Object, Query> {

  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  public QueryCoercer(
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  private Stream<BuildTarget> extractBuildTargets(CellNameResolver cellNameResolver, Query query) {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.empty(),
            Optional.empty(),
            typeCoercerFactory,
            cellNameResolver,
            unconfiguredBuildTargetFactory,
            query.getBaseName(),
            ImmutableSet.of(),
            query.getTargetConfiguration());
    QueryExpression<QueryBuildTarget> parsedExp;
    try {
      parsedExp = QueryExpression.<QueryBuildTarget>parse(query.getQuery(), env);
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing query: " + query.getQuery(), e);
    }
    return parsedExp.getTargets(env).stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return ((QueryBuildTarget) queryTarget).getBuildTarget();
            });
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (Class<?> type : types) {
      if (type.isAssignableFrom(BuildTarget.class)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void traverse(CellNameResolver cellRoots, Query query, Traversal traversal) {
    extractBuildTargets(cellRoots, query).forEach(traversal::traverse);
  }

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    return object;
  }

  @Override
  public TypeToken<Query> getOutputType() {
    return TypeToken.of(Query.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public Query coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      return Query.of(
          (String) object, targetConfiguration, BaseName.ofPath(pathRelativeToProjectRoot));
    }
    throw CoerceFailedException.simple(object, getOutputType());
  }
}
