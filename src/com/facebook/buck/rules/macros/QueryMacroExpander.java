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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Abstract base class for the query_targets and query_outputs macros */
public abstract class QueryMacroExpander<T extends QueryMacro>
    extends AbstractMacroExpander<T, QueryMacroExpander.QueryResults> {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();

  private Optional<TargetGraph> targetGraph;

  public QueryMacroExpander(Optional<TargetGraph> targetGraph) {
    this.targetGraph = targetGraph;
  }

  private Stream<BuildTarget> extractTargets(
      BuildTarget target,
      CellPathResolver cellNames,
      Optional<BuildRuleResolver> resolver,
      T input) {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            resolver,
            targetGraph,
            TYPE_COERCER_FACTORY,
            cellNames,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());
    try {
      QueryExpression parsedExp = QueryExpression.parse(input.getQuery().getQuery(), env);
      return parsedExp
          .getTargets(env)
          .stream()
          .map(
              queryTarget -> {
                Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
                return ((QueryBuildTarget) queryTarget).getBuildTarget();
              });
    } catch (QueryException e) {
      throw new HumanReadableException(
          e,
          "Error executing query in macro for target %s: %s",
          target,
          e.getHumanReadableErrorMessage());
    }
  }

  Stream<QueryTarget> resolveQuery(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String queryExpression)
      throws MacroException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(resolver),
            targetGraph,
            TYPE_COERCER_FACTORY,
            cellNames,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());
    try {
      QueryExpression parsedExp = QueryExpression.parse(queryExpression, env);
      Set<QueryTarget> queryTargets = new NoopQueryEvaluator().eval(parsedExp, env);
      return queryTargets.stream();
    } catch (QueryException e) {
      throw new MacroException("Error parsing/executing query from macro", e);
    }
  }

  @Override
  public Class<QueryResults> getPrecomputedWorkClass() {
    return QueryResults.class;
  }

  @Override
  public QueryResults precomputeWorkFrom(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver, T input)
      throws MacroException {
    return new QueryResults(resolveQuery(target, cellNames, resolver, input.getQuery().getQuery()));
  }

  abstract T fromQuery(Query query);

  @Override
  protected T parse(BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1) {
      throw new MacroException("One quoted query expression is expected");
    }
    return fromQuery(Query.of(input.get(0), target.getBaseName()));
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

  protected static final class QueryResults {
    ImmutableList<QueryTarget> results;

    public QueryResults(Stream<QueryTarget> results) {
      this.results = results.collect(ImmutableList.toImmutableList());
    }
  }
}
