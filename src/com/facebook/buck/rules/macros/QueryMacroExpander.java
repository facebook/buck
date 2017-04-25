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

package com.facebook.buck.rules.macros;

import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Abstract base class for the query_targets and query_outputs macros */
public abstract class QueryMacroExpander<T extends QueryMacro> extends AbstractMacroExpander<T> {

  private ListeningExecutorService executorService;
  private Optional<TargetGraph> targetGraph;

  public QueryMacroExpander(Optional<TargetGraph> targetGraph) {
    this.targetGraph = targetGraph;
    this.executorService = MoreExecutors.newDirectExecutorService();
  }

  private Stream<BuildTarget> extractTargets(
      BuildTarget target,
      CellPathResolver cellNames,
      Optional<BuildRuleResolver> resolver,
      T input) {
    String queryExpression = CharMatcher.anyOf("\"'").trimFrom(input.getQuery().getQuery());
    final GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            resolver,
            targetGraph,
            cellNames,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());
    try {
      QueryExpression parsedExp = QueryExpression.parse(queryExpression, env);
      HashSet<String> targetLiterals = new HashSet<>();
      parsedExp.collectTargetPatterns(targetLiterals);
      return targetLiterals
          .stream()
          .flatMap(
              pattern -> {
                try {
                  return env.getTargetsMatchingPattern(pattern, executorService).stream();
                } catch (Exception e) {
                  throw new HumanReadableException(
                      e, "Error parsing target expression %s for target %s", pattern, target);
                }
              })
          .map(
              queryTarget -> {
                Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
                return ((QueryBuildTarget) queryTarget).getBuildTarget();
              });
    } catch (QueryException e) {
      throw new HumanReadableException("Error executing query in macro for target %s", target, e);
    }
  }

  Stream<QueryTarget> resolveQuery(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      String queryExpression)
      throws MacroException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(resolver),
            targetGraph,
            cellNames,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());
    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(
            Optional.ofNullable(resolver.getEventBus()),
            PerfEventId.of("resolve_query_macro"),
            "target",
            target.toString())) {
      QueryExpression parsedExp = QueryExpression.parse(queryExpression, env);
      Set<QueryTarget> queryTargets = parsedExp.eval(env, executorService);
      return queryTargets.stream();
    } catch (QueryException e) {
      throw new MacroException("Error parsing/executing query from macro", e);
    } catch (InterruptedException e) {
      throw new MacroException("Error executing query", e);
    }
  }

  abstract T fromQuery(Query query);

  @Override
  protected final T parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1) {
      throw new MacroException("One quoted query expression is expected");
    }
    return fromQuery(Query.of(input.get(0)));
  }

  abstract boolean detectsTargetGraphOnlyDeps();

  @Override
  public void extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      T input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extractTargets(target, cellNames, Optional.empty(), input)
        .forEach(
            (detectsTargetGraphOnlyDeps() ? targetGraphOnlyDepsBuilder : buildDepsBuilder)::add);
  }
}
