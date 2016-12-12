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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Used to expand the macro {@literal $(query_outputs "some(query(:expression))")} to the
 * set of the outputs of the targets matching the query.
 * Example queries
 * <pre>
 *   '$(query_outputs "deps(:foo)")'
 *   '$(query_outputs "filter(bar, classpath(:bar))")'
 *   '$(query_outputs "attrfilter(annotation_processors, com.foo.Processor, deps(:app))")'
 * </pre>
 */
public class QueryOutputsMacroExpander extends QueryMacroExpander {

  public QueryOutputsMacroExpander(Optional<TargetGraph> targetGraph) {
    super(targetGraph);
  }

  @Override
  public String expand(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input) throws MacroException {
    if (input.isEmpty()) {
      throw new MacroException("One quoted query expression is expected");
    }
    String queryExpression = CharMatcher.anyOf("\"'").trimFrom(input.get(0));
    return resolveQuery(target, cellNames, resolver, queryExpression)
        .map(queryTarget -> {
          Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
          return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
        })
        .map(rule -> rule.getProjectFilesystem().resolve(rule.getPathToOutput()))
        .filter(Objects::nonNull)
        .map(Path::toString)
        .sorted()
        .collect(Collectors.joining(" "));
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

    // Return a list of SourcePaths to the outputs of our query results. This enables input-based
    // rule key hits.
    return resolveQuery(target, cellNames, resolver, queryExpression)
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
        .collect(MoreCollectors.toImmutableSortedSet(Ordering.natural()));
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
}
