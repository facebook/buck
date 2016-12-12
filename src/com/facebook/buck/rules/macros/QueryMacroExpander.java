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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for the query_targets and query_outputs macros
 */
public abstract class QueryMacroExpander implements MacroExpander {

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
      ImmutableList<String> input) throws MacroException {
    if (input.isEmpty()) {
      throw new MacroException("One quoted query expression is expected with optional flags");
    }
    String queryExpression = CharMatcher.anyOf("\"'").trimFrom(input.get(0));
    final GraphEnhancementQueryEnvironment env = new GraphEnhancementQueryEnvironment(
        resolver,
        targetGraph,
        cellNames,
        target,
        ImmutableSet.of());
    try {
      QueryExpression parsedExp = QueryExpression.parse(queryExpression, env);
      HashSet<String> targetLiterals = new HashSet<>();
      parsedExp.collectTargetPatterns(targetLiterals);
      return targetLiterals.stream()
          .flatMap(pattern -> {
            try {
              return env.getTargetsMatchingPattern(pattern, executorService).stream();
            } catch (Exception e) {
              throw new RuntimeException(new MacroException("Error parsing target expression", e));
            }
          })
          .map(queryTarget -> {
            Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
            return ((QueryBuildTarget) queryTarget).getBuildTarget();
          });
    } catch (QueryException e) {
      throw new MacroException("Error parsing query from macro", e);
    }
  }

  protected Stream<QueryTarget> resolveQuery(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      String queryExpression) throws MacroException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(resolver),
            targetGraph,
            cellNames,
            target,
            ImmutableSet.of());
    try {
      QueryExpression parsedExp = QueryExpression.parse(queryExpression, env);
      Set<QueryTarget> queryTargets = parsedExp.eval(env, executorService);
      return queryTargets.stream();
    } catch (QueryException e) {
      throw new MacroException("Error parsing/executing query from macro", e);
    } catch (InterruptedException e) {
      throw new MacroException("Error executing query", e);
    }
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {
    if (input.isEmpty()) {
      throw new MacroException("One quoted query expression is expected with optional flags");
    }
    String queryExpression = CharMatcher.anyOf("\"'").trimFrom(input.get(0));
    return ImmutableList.copyOf(resolveQuery(target, cellNames, resolver, queryExpression)
        .map(queryTarget -> {
          Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
          return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
        })
        .sorted()
        .collect(Collectors.toList()));
  }

  @Override
  public ImmutableList<BuildTarget> extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input) throws MacroException {
    return ImmutableList.copyOf(extractTargets(target, cellNames, Optional.empty(), input)
        .collect(Collectors.toList()));
  }

  @Override
  public Object extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {
    if (input.isEmpty()) {
      throw new MacroException("One quoted query expression is expected");
    }
    String queryExpression = CharMatcher.anyOf("\"'").trimFrom(input.get(0));
    return ImmutableSortedSet.copyOf(resolveQuery(target, cellNames, resolver, queryExpression)
        .map(queryTarget -> {
          Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
          try {
            return resolver.requireRule(((QueryBuildTarget) queryTarget).getBuildTarget());
          } catch (NoSuchBuildTargetException e) {
            throw new RuntimeException(
                new MacroException("Error extracting rule key appendables", e));
          }
        })
        .filter(rule -> rule.getPathToOutput() != null)
        .map(rule -> SourcePaths.getToBuildTargetSourcePath().apply(rule))
        .collect(Collectors.toSet()));
  }

}
