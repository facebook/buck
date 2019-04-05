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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Abstract base class for the query_targets and query_outputs macros */
public abstract class QueryMacroExpander<T extends QueryMacro>
    implements MacroExpander<T, QueryMacroExpander.QueryResults> {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();
  private static final UnconfiguredBuildTargetFactory UNCONFIGURED_BUILD_TARGET_FACTORY =
      new ParsingUnconfiguredBuildTargetFactory();

  private Optional<TargetGraph> targetGraph;

  public QueryMacroExpander(Optional<TargetGraph> targetGraph) {
    this.targetGraph = targetGraph;
  }

  Stream<QueryTarget> resolveQuery(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      String queryExpression)
      throws MacroException {
    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            targetGraph,
            TYPE_COERCER_FACTORY,
            cellNames,
            UNCONFIGURED_BUILD_TARGET_FACTORY,
            target.getBaseName(),
            ImmutableSet.of(),
            target.getTargetConfiguration());
    try {
      QueryExpression<QueryBuildTarget> parsedExp = QueryExpression.parse(queryExpression, env);
      Set<QueryTarget> queryTargets =
          new NoopQueryEvaluator<QueryBuildTarget>().eval(parsedExp, env);
      return queryTargets.stream();
    } catch (QueryException e) {
      throw new MacroException("Error parsing/executing query from macro", e);
    }
  }

  @Override
  public QueryResults precomputeWorkFrom(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, T input)
      throws MacroException {
    return new QueryResults(
        resolveQuery(target, cellNames, graphBuilder, input.getQuery().getQuery()));
  }

  protected static final class QueryResults {
    ImmutableList<QueryTarget> results;

    public QueryResults(Stream<QueryTarget> results) {
      this.results = results.collect(ImmutableList.toImmutableList());
    }
  }
}
