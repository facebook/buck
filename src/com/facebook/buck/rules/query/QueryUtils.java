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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasApplicationModuleBlacklist;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasProvidedDepsQuery;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.Threads;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mixin class to allow dynamic dependency resolution at graph enhancement time. New and unstable.
 * Will almost certainly change in interface and implementation.
 */
public final class QueryUtils {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();
  private static final UnconfiguredBuildTargetViewFactory UNCONFIGURED_BUILD_TARGET_FACTORY =
      new ParsingUnconfiguredBuildTargetViewFactory();

  private QueryUtils() {
    // This class cannot be instantiated
  }

  @SuppressWarnings("unchecked")
  public static <T> T withDepsQuery(
      T arg,
      BuildTarget target,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellNameResolver cellRoots,
      TargetGraph graph) {
    if (arg instanceof HasDepsQuery) {
      HasDepsQuery castedArg = (HasDepsQuery) arg;
      if (castedArg.getDepsQuery().isPresent()) {
        Query query = castedArg.getDepsQuery().get();
        ImmutableSortedSet<BuildTarget> resolvedQuery =
            resolveDepQuery(
                target, query, cache, graphBuilder, cellRoots, graph, castedArg.getDeps());
        return (T) castedArg.withDepsQuery(query.withResolvedQuery(resolvedQuery));
      }
    }

    return arg;
  }

  @SuppressWarnings("unchecked")
  public static <T> T withProvidedDepsQuery(
      T arg,
      BuildTarget target,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellNameResolver cellRoots,
      TargetGraph graph) {
    if (arg instanceof HasProvidedDepsQuery) {
      HasProvidedDepsQuery castedArg = (HasProvidedDepsQuery) arg;
      if (castedArg.getProvidedDepsQuery().isPresent()) {
        Query query = castedArg.getProvidedDepsQuery().get();
        ImmutableSortedSet<BuildTarget> resolvedQuery =
            resolveDepQuery(
                target, query, cache, graphBuilder, cellRoots, graph, castedArg.getProvidedDeps());
        arg = (T) castedArg.withProvidedDepsQuery(query.withResolvedQuery(resolvedQuery));
      }
    }

    return arg;
  }

  /**
   * Utility method for resolving queries in application_module_blacklist
   *
   * @param arg
   * @param target
   * @param cache
   * @param graphBuilder
   * @param cellRoots
   * @param graph
   * @param <T>
   * @return args with the queries in application_module_blacklist resolved
   */
  @SuppressWarnings("unchecked")
  public static <T> T withModuleBlacklistQuery(
      T arg,
      BuildTarget target,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellNameResolver cellRoots,
      TargetGraph graph) {
    if (arg instanceof HasApplicationModuleBlacklist) {
      HasApplicationModuleBlacklist castedArg = (HasApplicationModuleBlacklist) arg;
      if (castedArg.getApplicationModuleBlacklist().isPresent()) {
        List<Query> queries = castedArg.getApplicationModuleBlacklist().get();

        ImmutableList<Query> resolvedQueries =
            queries.stream()
                .map(
                    query ->
                        query.withResolvedQuery(
                            resolveDepQuery(
                                target,
                                query,
                                cache,
                                graphBuilder,
                                cellRoots,
                                graph,
                                ImmutableSet.of())))
                .collect(ImmutableList.toImmutableList());

        arg = (T) castedArg.withApplicationModuleBlacklist(resolvedQueries);
      }
    }

    return arg;
  }

  private static ImmutableSortedSet<BuildTarget> resolveDepQuery(
      BuildTarget target,
      Query query,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellNameResolver cellRoots,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> declaredDeps) {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            Optional.of(targetGraph),
            TYPE_COERCER_FACTORY,
            cellRoots,
            UNCONFIGURED_BUILD_TARGET_FACTORY,
            target.getBaseName(),
            declaredDeps,
            target.getTargetConfiguration());
    try {
      QueryExpression<QueryBuildTarget> parsedExp = QueryExpression.parse(query.getQuery(), env);
      Set<QueryBuildTarget> queryTargets =
          cache.getQueryEvaluator(targetGraph).eval(parsedExp, env);
      return queryTargets.stream()
          .map(queryTarget -> queryTarget.getBuildTarget())
          .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        Threads.interruptCurrentThread();
      }
      throw new RuntimeException("Error parsing/executing query from deps for " + target, e);
    }
  }

  public static Stream<BuildTarget> extractBuildTargets(
      CellNameResolver cellNameResolver, BaseName targetBaseName, Query query)
      throws QueryException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.empty(),
            Optional.empty(),
            TYPE_COERCER_FACTORY,
            cellNameResolver,
            UNCONFIGURED_BUILD_TARGET_FACTORY,
            targetBaseName,
            ImmutableSet.of(),
            query.getTargetConfiguration());
    QueryExpression<QueryBuildTarget> parsedExp = QueryExpression.parse(query.getQuery(), env);
    return parsedExp.getTargets(env).stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return ((QueryBuildTarget) queryTarget).getBuildTarget();
            });
  }

  public static Stream<BuildTarget> extractParseTimeTargets(
      BuildTarget target, CellNameResolver cellNames, Query query) {
    try {
      return extractBuildTargets(cellNames, target.getBaseName(), query);
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing/executing query from deps for " + target, e);
    }
  }
}
