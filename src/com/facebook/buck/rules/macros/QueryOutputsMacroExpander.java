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
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
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
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryOutputsMacro input,
      QueryResults precomputedWork) {
    return new QueriedOutputsArg(
        precomputedWork
            .results
            .stream()
            .map(
                queryTarget -> {
                  Preconditions.checkState(queryTarget instanceof QueryBuildTarget);
                  return resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
                })
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .sorted()
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  boolean detectsTargetGraphOnlyDeps() {
    return false;
  }

  private static class QueriedOutputsArg implements Arg {
    @AddToRuleKey private final ImmutableList<SourcePath> queriedOutputs;

    public QueriedOutputsArg(ImmutableList<SourcePath> queriedOutputs) {
      this.queriedOutputs = queriedOutputs;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      // TODO(cjhopman): The sorted() call could feasibly (though unlikely) return different
      // ordering in different contexts.
      consumer.accept(
          queriedOutputs
              .stream()
              .map(pathResolver::getAbsolutePath)
              .map(Path::toString)
              .sorted()
              .collect(Collectors.joining(" ")));
    }
  }
}
