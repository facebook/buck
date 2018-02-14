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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.HumanReadableException;
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
  QueryPathsMacro fromQuery(Query query) {
    return QueryPathsMacro.of(query);
  }

  @Override
  boolean detectsTargetGraphOnlyDeps() {
    return false;
  }

  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryPathsMacro input,
      QueryResults precomputedWork)
      throws MacroException {
    return new QueriedPathsArg(
        precomputedWork
            .results
            .stream()
            .map(
                queryTarget -> {
                  // What we do depends on the input.
                  if (QueryBuildTarget.class.isAssignableFrom(queryTarget.getClass())) {
                    BuildRule rule =
                        resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
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
          queriedPaths
              .stream()
              .map(pathResolver::getAbsolutePath)
              .map(Object::toString)
              .sorted()
              .collect(Collectors.joining(" ")));
    }
  }
}
