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

package com.facebook.buck.cli;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A 'deps(x [, depth])' expression, which finds the dependencies of the given argument set 'x'.
 * The optional parameter 'depth' specifies the depth of the search. If the argument is absent,
 * the search is unbounded.
 *
 * <pre>expr ::= DEPS '(' expr ')'</pre>
 * <pre>       | DEPS '(' expr ',' WORD ')'</pre>
 */
public class QueryDepsFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.EXPRESSION, ArgumentType.INTEGER);

  public QueryDepsFunction() {
  }

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

  /**
   * Evaluates to the dependencies of the argument.
   * Breadth first search from the given argument until there are no more unvisited nodes in the
   * transitive closure or the maximum depth (if supplied) is reached.
   */
  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    Set<T> argumentSet = args.get(0).getExpression().eval(env);
    int depthBound = args.size() > 1 ? args.get(1).getInteger() : Integer.MAX_VALUE;
    env.buildTransitiveClosure(expression, argumentSet, depthBound);

    // LinkedHashSet preserves the order of insertion when iterating over the values.
    // The order by which we traverse the result is meaningful because the dependencies are
    // traversed level-by-level.
    Set<T> result = new LinkedHashSet<>();
    Collection<T> current = argumentSet;

    // Iterating depthBound+1 times because the first one processes the given argument set.
    for (int i = 0; i <= depthBound; i++) {
      Collection<T> next = env.getFwdDeps(
          Iterables.filter(current, Predicates.not(Predicates.in(result))));
      result.addAll(current);
      if (next.isEmpty()) {
        break;
      }
      current = next;
    }
    return result;
  }

}
