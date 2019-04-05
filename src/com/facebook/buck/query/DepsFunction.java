/*
 * Copyright 2015-present Facebook, Inc.
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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A 'deps(x [, depth, next_expr])' expression, which finds the dependencies of the given argument
 * set 'x'. The optional parameter 'depth' specifies the depth of the search. If 'depth' is absent,
 * the search is unbounded. The optional third argument specifies how new edges are added to the
 * traversal. If the 'next_expr' is absent, the default 'first_order_deps()' function is used.
 *
 * <pre>expr ::= DEPS '(' expr ')'</pre>
 *
 * <pre>       | DEPS '(' expr ',' INTEGER ')'</pre>
 *
 * <pre>       | DEPS '(' expr ',' INTEGER ',' expr ')'</pre>
 */
public class DepsFunction implements QueryFunction<QueryBuildTarget, QueryBuildTarget> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.EXPRESSION, ArgumentType.INTEGER, ArgumentType.EXPRESSION);

  public DepsFunction() {}

  @Override
  public String getName() {
    return "deps";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  private void forEachDep(
      QueryEnvironment<QueryBuildTarget> env,
      QueryExpression<QueryBuildTarget> depsExpression,
      Iterable<QueryBuildTarget> targets,
      Consumer<QueryBuildTarget> consumer)
      throws QueryException {
    for (QueryBuildTarget target : targets) {
      ImmutableSet<QueryBuildTarget> deps =
          depsExpression.eval(
              new NoopQueryEvaluator<QueryBuildTarget>(),
              new TargetVariablesQueryEnvironment<QueryBuildTarget>(
                  ImmutableMap.of(
                      FirstOrderDepsFunction.NAME,
                      ImmutableSet.copyOf(env.getFwdDeps(ImmutableList.of(target))),
                      "@this",
                      ImmutableSet.of(target)),
                  env));
      deps.forEach(consumer);
    }
  }

  /**
   * Evaluates to the dependencies of the argument. Breadth first search from the given argument
   * until there are no more unvisited nodes in the transitive closure or the maximum depth (if
   * supplied) is reached.
   */
  @Override
  public ImmutableSet<QueryBuildTarget> eval(
      QueryEvaluator<QueryBuildTarget> evaluator,
      QueryEnvironment<QueryBuildTarget> env,
      ImmutableList<Argument<QueryBuildTarget>> args)
      throws QueryException {
    ImmutableSet<QueryBuildTarget> argumentSet = evaluator.eval(args.get(0).getExpression(), env);
    int depthBound = args.size() > 1 ? args.get(1).getInteger() : Integer.MAX_VALUE;
    Optional<QueryExpression<QueryBuildTarget>> deps =
        args.size() > 2 ? Optional.of(args.get(2).getExpression()) : Optional.empty();
    env.buildTransitiveClosure(argumentSet, depthBound);

    // LinkedHashSet preserves the order of insertion when iterating over the values.
    // The order by which we traverse the result is meaningful because the dependencies are
    // traversed level-by-level.
    Set<QueryBuildTarget> result = new LinkedHashSet<>(argumentSet);
    Collection<QueryBuildTarget> current = argumentSet;

    // Iterating depthBound+1 times because the first one processes the given argument set.
    for (int i = 0; i < depthBound; i++) {
      Collection<QueryBuildTarget> next = new ArrayList<>();
      Consumer<QueryBuildTarget> consumer =
          queryTarget -> {
            boolean added = result.add(queryTarget);
            if (added) {
              next.add(queryTarget);
            }
          };
      if (deps.isPresent()) {
        forEachDep(env, deps.get(), current, consumer);
      } else {
        env.forEachFwdDep(current, consumer);
      }
      if (next.isEmpty()) {
        break;
      }
      current = next;
    }
    return ImmutableSet.copyOf(result);
  }

  /**
   * A function that resolves to the current node's target being traversed when evaluating the deps
   * function.
   */
  public static class FirstOrderDepsFunction
      implements QueryFunction<QueryBuildTarget, QueryBuildTarget> {

    private static final String NAME = "first_order_deps";

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public int getMandatoryArguments() {
      return 0;
    }

    @Override
    public ImmutableList<ArgumentType> getArgumentTypes() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableSet<QueryBuildTarget> eval(
        QueryEvaluator<QueryBuildTarget> evaluator,
        QueryEnvironment<QueryBuildTarget> env,
        ImmutableList<Argument<QueryBuildTarget>> args) {
      Preconditions.checkArgument(args.isEmpty());
      return env.resolveTargetVariable(getName());
    }
  }

  /** A function that looks up target variables by name */
  public static class LookupFunction<OUTPUT_TYPE extends QueryTarget, ENV_NODE_TYPE>
      implements QueryFunction<OUTPUT_TYPE, ENV_NODE_TYPE> {
    @Override
    public String getName() {
      return "lookup";
    }

    @Override
    public int getMandatoryArguments() {
      return 1;
    }

    @Override
    public ImmutableList<ArgumentType> getArgumentTypes() {
      return ImmutableList.of(ArgumentType.WORD);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImmutableSet<OUTPUT_TYPE> eval(
        QueryEvaluator<ENV_NODE_TYPE> evaluator,
        QueryEnvironment<ENV_NODE_TYPE> env,
        ImmutableList<Argument<ENV_NODE_TYPE>> args) {
      Preconditions.checkArgument(args.size() == 1);
      return (ImmutableSet<OUTPUT_TYPE>) env.resolveTargetVariable(args.get(0).getWord());
    }
  }
}
