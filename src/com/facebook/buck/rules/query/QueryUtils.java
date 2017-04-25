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

import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mixin class to allow dynamic dependency resolution at graph enhancement time. New and unstable.
 * Will almost certainly change in interface and implementation.
 */
public final class QueryUtils {

  private QueryUtils() {
    // This class cannot be instantiated
  }

  public static Stream<BuildRule> resolveDepQuery(
      BuildTarget target,
      Query query,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> declaredDeps) {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(resolver),
            Optional.of(targetGraph),
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            declaredDeps);
    ListeningExecutorService executorService = MoreExecutors.newDirectExecutorService();
    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(
            Optional.ofNullable(resolver.getEventBus()),
            PerfEventId.of("resolve_dep_query"),
            "target",
            target.toString())) {
      QueryExpression parsedExp = QueryExpression.parse(query.getQuery(), env);
      Set<QueryTarget> queryTargets = parsedExp.eval(env, executorService);
      return queryTargets
          .stream()
          .map(
              queryTarget -> {
                Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
                return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
              });
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing/executing query from deps for " + target, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error executing query from deps for " + target, e);
    }
  }

  public static Stream<BuildTarget> extractBuildTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> parserPattern,
      Query query)
      throws QueryException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.empty(), Optional.empty(), cellPathResolver, parserPattern, ImmutableSet.of());
    ListeningExecutorService executorService = MoreExecutors.newDirectExecutorService();
    QueryExpression parsedExp = QueryExpression.parse(query.getQuery(), env);
    List<String> targetLiterals = new ArrayList<>();
    parsedExp.collectTargetPatterns(targetLiterals);
    return targetLiterals
        .stream()
        .flatMap(
            pattern -> {
              try {
                return env.getTargetsMatchingPattern(pattern, executorService).stream();
              } catch (Exception e) {
                throw new RuntimeException("Error parsing target expression", e);
              }
            })
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
