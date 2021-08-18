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

package com.facebook.buck.features.project.intellij.depsquery;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasProvidedDepsQuery;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryCache;
import com.facebook.buck.rules.query.QueryUtils;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.Set;

/** This class resolves deps_query and provided_deps_query without action graph */
public class DefaultIjDepsQueryResolver implements IjDepsQueryResolver {

  private final TargetGraph targetGraph;
  private final TypeCoercerFactory typeCoercerFactory;
  private final CellNameResolver cellNameResolver;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory;
  private final QueryCache cache;

  public DefaultIjDepsQueryResolver(
      TargetGraph targetGraph,
      TypeCoercerFactory typeCoercerFactory,
      CellNameResolver cellNameResolver,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory) {
    this.targetGraph = targetGraph;
    this.typeCoercerFactory = typeCoercerFactory;
    this.cellNameResolver = cellNameResolver;
    this.unconfiguredBuildTargetViewFactory = unconfiguredBuildTargetViewFactory;
    this.cache = new QueryCache();
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getResolvedDeps(TargetNode<?> targetNode) {
    Object arg = targetNode.getConstructorArg();
    if (!(arg instanceof HasDepsQuery)) {
      return ImmutableSortedSet.of();
    }
    HasDepsQuery castedArg = (HasDepsQuery) arg;
    Optional<Query> depsQuery = castedArg.getDepsQuery();
    if (!depsQuery.isPresent()) {
      return ImmutableSortedSet.of();
    }
    return QueryUtils.resolveDepQuery(
        targetNode.getBuildTarget(),
        depsQuery.get(),
        cache,
        targetGraph,
        createGraphEnhancementQueryEnvironment(targetNode.getBuildTarget(), castedArg.getDeps()));
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getResolvedProvidedDeps(TargetNode<?> targetNode) {
    Object arg = targetNode.getConstructorArg();
    if (!(arg instanceof HasProvidedDepsQuery)) {
      return ImmutableSortedSet.of();
    }
    HasProvidedDepsQuery castedArg = (HasProvidedDepsQuery) arg;
    Optional<Query> providedDepsQuery = castedArg.getProvidedDepsQuery();
    if (!providedDepsQuery.isPresent()) {
      return ImmutableSortedSet.of();
    }
    return QueryUtils.resolveDepQuery(
        targetNode.getBuildTarget(),
        providedDepsQuery.get(),
        cache,
        targetGraph,
        createGraphEnhancementQueryEnvironment(
            targetNode.getBuildTarget(), castedArg.getProvidedDeps()));
  }

  private GraphEnhancementQueryEnvironment createGraphEnhancementQueryEnvironment(
      BuildTarget buildTarget, Set<BuildTarget> deps) {
    return new IjGraphEnhancementQueryEnvironment(
        targetGraph,
        typeCoercerFactory,
        cellNameResolver,
        unconfiguredBuildTargetViewFactory,
        buildTarget.getBaseName(),
        deps,
        buildTarget.getTargetConfiguration());
  }
}
