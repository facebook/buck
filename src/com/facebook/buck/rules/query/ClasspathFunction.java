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

package com.facebook.buck.rules.query;

import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A classpath(expression [, depth]) expression that calculates targets in the classpath of the
 * given library or libraries.
 *
 * <pre>expr ::= CLASSPATH '(' expr ')'</pre>
 *
 * <pre>       | CLASSPATH '(' expr ',' INTEGER ')'</pre>
 */
public class ClasspathFunction implements QueryEnvironment.QueryFunction {
  @Override
  public String getName() {
    return "classpath";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<QueryEnvironment.ArgumentType> getArgumentTypes() {
    return ImmutableList.of(
        QueryEnvironment.ArgumentType.EXPRESSION, QueryEnvironment.ArgumentType.INTEGER);
  }

  @Override
  public ImmutableSet<QueryTarget> eval(
      QueryEnvironment env,
      ImmutableList<QueryEnvironment.Argument> args,
      ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    Preconditions.checkArgument(env instanceof GraphEnhancementQueryEnvironment);
    Set<QueryTarget> argumentSet = args.get(0).getExpression().eval(env, executor);

    int depthBound = args.size() >= 2 ? args.get(1).getInteger() : Integer.MAX_VALUE;
    Set<QueryTarget> result = new LinkedHashSet<>(argumentSet);
    Set<QueryTarget> current = argumentSet;

    for (int i = 0; i < depthBound; i++) {
      Set<QueryTarget> next = new LinkedHashSet<>();
      Consumer<? super QueryTarget> consumer =
          queryTarget -> {
            boolean added = result.add(queryTarget);
            if (added) {
              next.add(queryTarget);
            }
          };
      ((GraphEnhancementQueryEnvironment) env).getFirstOrderClasspath(current).forEach(consumer);
      if (next.isEmpty()) {
        break;
      }
      current = next;
    }
    return ImmutableSet.copyOf(result);
  }
}
