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
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.rules.query.UnconfiguredQuery;
import com.google.common.reflect.TypeToken;
import java.util.stream.Stream;

/** Coercer for {@link Query}s. */
public class QueryCoercer implements TypeCoercer<UnconfiguredQuery, Query> {

  private Stream<UnconfiguredBuildTarget> extractUnconfiguredBuildTargets(
      CellNameResolver cellNameResolver, UnconfiguredQuery query) {
    try {
      return QueryUtils.extractUnconfiguredBuildTargets(
          cellNameResolver, query.getBaseName(), query);
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing query: " + query.getQuery(), e);
    }
  }

  private Stream<BuildTarget> extractBuildTargets(CellNameResolver cellNameResolver, Query query) {
    try {
      return QueryUtils.extractBuildTargets(cellNameResolver, query.getBaseName(), query);
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing query: " + query.getQuery(), e);
    }
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
  public void traverseUnconfigured(
      CellNameResolver cellRoots, UnconfiguredQuery query, Traversal traversal) {
    extractUnconfiguredBuildTargets(cellRoots, query).forEach(traversal::traverse);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, Query query, Traversal traversal) {
    extractBuildTargets(cellRoots, query).forEach(traversal::traverse);
  }

  @Override
  public UnconfiguredQuery coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      return UnconfiguredQuery.of((String) object, BaseName.ofPath(pathRelativeToProjectRoot));
    }
    throw CoerceFailedException.simple(object, getOutputType());
  }

  @Override
  public TypeToken<Query> getOutputType() {
    return TypeToken.of(Query.class);
  }

  @Override
  public TypeToken<UnconfiguredQuery> getUnconfiguredType() {
    return TypeToken.of(UnconfiguredQuery.class);
  }

  @Override
  public Query coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      HostTargetConfigurationResolver hostConfigurationResolver,
      UnconfiguredQuery object)
      throws CoerceFailedException {
    return object.configure(targetConfiguration);
  }
}
