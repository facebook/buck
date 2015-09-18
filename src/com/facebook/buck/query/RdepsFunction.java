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
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A 'rdeps(u, x, [, depth])' expression, which finds the reverse dependencies of the given
 * argument set 'x' within the transitive closure of the set 'u'.
 * The optional parameter 'depth' specifies the depth of the search. If the argument is absent,
 * the search is unbounded.
 *
 * <pre>expr ::= RDEPS '(' expr ',' expr ')'</pre>
 * <pre>       | RDEPS '(' expr ',' expr ',' WORD ')'</pre>
 */
public class RdepsFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.EXPRESSION, ArgumentType.EXPRESSION, ArgumentType.INTEGER);

  public RdepsFunction() {
  }

  @Override
  public String getName() {
    return "rdeps";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  /**
   * Evaluates to the reverse dependencies of the argument 'x' in the transitive closure of the
   * set 'u'. Breadth first search from the set 'x' until there are no more unvisited nodes in the
   * reverse transitive closure or the maximum depth (if supplied) is reached.
   */
  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, ImmutableList<Argument> args)
      throws QueryException, InterruptedException {
    Set<T> universeSet = args.get(0).getExpression().eval(env);
    env.buildTransitiveClosure(universeSet, Integer.MAX_VALUE);
    final Predicate<T> inUniversePredicate = Predicates.in(env.getTransitiveClosure(universeSet));

    // LinkedHashSet preserves the order of insertion when iterating over the values.
    // The order by which we traverse the result is meaningful because the dependencies are
    // traversed level-by-level.
    Set<T> visited = new LinkedHashSet<>();
    Set<T> argumentSet = args.get(1).getExpression().eval(env);
    Collection<T> current = argumentSet;

    int depthBound = args.size() > 2 ? args.get(2).getInteger() : Integer.MAX_VALUE;
    // Iterating depthBound+1 times because the first one processes the given argument set.
    for (int i = 0; i <= depthBound; i++) {
      // Restrict the search to nodes in the transitive closure of the universe set.
      Iterable<T> currentInUniverse = Iterables.filter(current, inUniversePredicate);

      // Filter nodes visited before.
      Collection<T> next = env.getReverseDeps(
          Iterables.filter(currentInUniverse, Predicates.not(Predicates.in(visited))));
      Iterables.addAll(visited, currentInUniverse);
      if (next.isEmpty()) {
        break;
      }
      current = next;
    }
    return visited;
  }

}
