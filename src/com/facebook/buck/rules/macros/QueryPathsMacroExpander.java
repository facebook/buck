/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class QueryPathsMacroExpander extends QueryMacroExpander<QueryPathsMacro> {

  public QueryPathsMacroExpander(Optional<TargetGraph> targetGraph) {
    super(targetGraph);
  }

  @Override
  public Class<QueryPathsMacro> getInputClass() {
    return QueryPathsMacro.class;
  }

  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      QueryPathsMacro input,
      QueryResults precomputedWork) {
    return new QueriedPathsArg(
        precomputedWork.results.stream()
            .map(
                queryTarget -> {
                  // What we do depends on the input.
                  if (QueryBuildTarget.class.isAssignableFrom(queryTarget.getClass())) {
                    BuildRule rule =
                        graphBuilder.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
                    return Optional.ofNullable(rule.getSourcePathToOutput()).orElse(null);
                  } else if (QueryFileTarget.class.isAssignableFrom(queryTarget.getClass())) {
                    return ((QueryFileTarget) queryTarget).getPath();
                  } else {
                    throw new HumanReadableException("Unknown query target type: " + queryTarget);
                  }
                })
            .filter(Objects::nonNull));
  }

  private class QueriedPathsArg implements Arg {
    @AddToRuleKey private final ImmutableList<SourcePath> queriedPaths;

    public QueriedPathsArg(Stream<SourcePath> queriedPaths) {
      this.queriedPaths = queriedPaths.collect(ImmutableList.toImmutableList());
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      // TODO(cjhopman): The sorted() call could feasibly (though unlikely) return different
      // ordering in different contexts.
      consumer.accept(
          queriedPaths.stream()
              .map(pathResolver::getAbsolutePath)
              .map(Object::toString)
              .map(Escaper::escapeAsShellString)
              .sorted()
              .collect(Collectors.joining(" ")));
    }
  }
}
