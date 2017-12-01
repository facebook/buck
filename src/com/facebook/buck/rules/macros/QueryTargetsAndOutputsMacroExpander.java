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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Used to expand the macro {@literal $(query_targets_and_outputs "some(query(:expression))")} to
 * the full target names and the set of the outputs of the targets matching the query separated by a
 * space. Example queries
 *
 * <pre>
 *   '$(query_targets_and_outputs "deps(:foo)")'
 *   '$(query_targets_and_outputs "filter(bar, classpath(:bar))")'
 *   '$(query_targets_and_outputs "attrfilter(annotation_processors, com.foo.Processor, deps(:app))")'
 * </pre>
 *
 * Example output:
 *
 * <pre>
 * $(query_targets_and_outputs "deps(:foo)") ->
 *   "//:bar1 /tmp/project/buck-out/gen/bar1/out_file //:bar2 /tmp/project/buck-out/gen/bar2/out_file"
 * </pre>
 */
public class QueryTargetsAndOutputsMacroExpander
    extends QueryMacroExpander<QueryTargetsAndOutputsMacro> {

  public QueryTargetsAndOutputsMacroExpander(Optional<TargetGraph> targetGraph) {
    super(targetGraph);
  }

  @Override
  public Class<QueryTargetsAndOutputsMacro> getInputClass() {
    return QueryTargetsAndOutputsMacro.class;
  }

  @Override
  public QueryTargetsAndOutputsMacro fromQuery(Query query) {
    throw new IllegalArgumentException(
        "A separator must be provided to create a QueryTargetsAndOutputsMacro object");
  }

  @Override
  public String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryTargetsAndOutputsMacro input,
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
        .map(
            rule -> {
              SourcePath sourcePath = rule.getSourcePathToOutput();
              if (sourcePath == null) {
                return null;
              }
              return String.format(
                  "%s%s%s",
                  rule.getBuildTarget().getFullyQualifiedName(),
                  input.getSeparator(),
                  pathResolver.getAbsolutePath(sourcePath));
            })
        .filter(Objects::nonNull)
        .sorted()
        .collect(Collectors.joining(input.getSeparator()));
  }

  @Override
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      QueryTargetsAndOutputsMacro input,
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
      QueryTargetsAndOutputsMacro input,
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

  @Override
  protected QueryTargetsAndOutputsMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {

    String separator = " ";
    String query;
    if (input.size() == 2) {
      separator = CharMatcher.anyOf("\"'").trimFrom(input.get(0));
      query = input.get(1);
    } else if (input.size() == 1) {
      query = input.get(0);
    } else {
      throw new MacroException(
          "One quoted query expression is expected, or a separator and a query");
    }
    return QueryTargetsAndOutputsMacro.of(separator, Query.of(query));
  }
}
