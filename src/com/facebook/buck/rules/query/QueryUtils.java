/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasProvidedDepsQuery;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.Threads;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mixin class to allow dynamic dependency resolution at graph enhancement time. New and unstable.
 * Will almost certainly change in interface and implementation.
 */
public final class QueryUtils {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();

  private QueryUtils() {
    // This class cannot be instantiated
  }

  @SuppressWarnings("unchecked")
  public static <T> T withDepsQuery(
      T arg,
      BuildTarget target,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
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
      CellPathResolver cellRoots,
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

  private static ImmutableSortedSet<BuildTarget> resolveDepQuery(
      BuildTarget target,
      Query query,
      QueryCache cache,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> declaredDeps) {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            Optional.of(targetGraph),
            TYPE_COERCER_FACTORY,
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            declaredDeps);
    try {
      QueryExpression parsedExp = QueryExpression.parse(query.getQuery(), env);
      Set<QueryTarget> queryTargets = cache.getQueryEvaluator(targetGraph).eval(parsedExp, env);
      return queryTargets
          .stream()
          .map(
              queryTarget -> {
                Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
                return ((QueryBuildTarget) queryTarget).getBuildTarget();
              })
          .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        Threads.interruptCurrentThread();
      }
      throw new RuntimeException("Error parsing/executing query from deps for " + target, e);
    }
  }

  public static Stream<BuildTarget> extractBuildTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> parserPattern,
      Query query)
      throws QueryException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.empty(),
            Optional.empty(),
            TYPE_COERCER_FACTORY,
            cellPathResolver,
            parserPattern,
            ImmutableSet.of());
    QueryExpression parsedExp = QueryExpression.parse(query.getQuery(), env);
    return parsedExp
        .getTargets(env)
        .stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return ((QueryBuildTarget) queryTarget).getBuildTarget();
            });
  }

  public static Stream<BuildTarget> extractParseTimeTargets(
      BuildTarget target, CellPathResolver cellNames, Query query) {
    try {
      return extractBuildTargets(
          cellNames, BuildTargetPatternParser.forBaseName(target.getBaseName()), query);
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing/executing query from deps for " + target, e);
    }
  }
}
