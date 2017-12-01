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
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Used to expand the macro {@literal $(query_outputs "some(query(:expression))")} to the set of the
 * outputs of the targets matching the query. Example queries
 *
 * <pre>
 *   '$(query_outputs "deps(:foo)")'
 *   '$(query_outputs "filter(bar, classpath(:bar))")'
 *   '$(query_outputs "attrfilter(annotation_processors, com.foo.Processor, deps(:app))")'
 * </pre>
 */
public class QueryOutputsMacroExpander extends QueryMacroExpander<QueryOutputsMacro> {

  public QueryOutputsMacroExpander(Optional<TargetGraph> targetGraph) {
    super(targetGraph);
  }

  @Override
  public Class<QueryOutputsMacro> getInputClass() {
    return QueryOutputsMacro.class;
  }

  @Override
  public QueryOutputsMacro fromQuery(Query query) {
    return QueryOutputsMacro.of(query);
  }

  @Override
  public String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryOutputsMacro input,
      QueryResults precomputedWork)
      throws MacroException {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    return precomputedWork
        .results
        .stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
            })
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .map(pathResolver::getAbsolutePath)
        .map(Path::toString)
        .sorted()
        .collect(Collectors.joining(" "));
  }

  @Override
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      QueryOutputsMacro input,
      QueryResults precomputedWork)
      throws MacroException {

    // Return a list of SourcePaths to the outputs of our query results. This enables input-based
    // rule key hits.
    return precomputedWork
        .results
        .stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return resolver.requireRule(((QueryBuildTarget) queryTarget).getBuildTarget());
            })
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  boolean detectsTargetGraphOnlyDeps() {
    return false;
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      QueryOutputsMacro input,
      QueryResults precomputedWork)
      throws MacroException {
    return precomputedWork
        .results
        .stream()
        .map(
            queryTarget -> {
              Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
              return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
            })
        .sorted()
        .collect(ImmutableList.toImmutableList());
  }
}
