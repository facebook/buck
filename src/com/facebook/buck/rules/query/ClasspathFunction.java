/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.query;

import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEvaluator;
import com.facebook.buck.query.QueryException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
public class ClasspathFunction<T> implements QueryEnvironment.QueryFunction<T> {
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

  private SupportsClasspathEnvironment<T> assertSupportsClasspath(QueryEnvironment<T> env) {
    if (!(env instanceof SupportsClasspathEnvironment)) {
      throw new IllegalStateException(
          "classpath function used by query environment doesn't support it: " + env);
    }
    return (SupportsClasspathEnvironment<T>) env;
  }

  /**
   * @param genericEnv must implement {@link SupportsClasspathEnvironment} or you will get a runtime
   *     {@link IllegalStateException}.
   */
  @Override
  public ImmutableSet<T> eval(
      QueryEvaluator<T> evaluator, QueryEnvironment<T> genericEnv, ImmutableList<Argument<T>> args)
      throws QueryException {
    SupportsClasspathEnvironment<T> env = assertSupportsClasspath(genericEnv);
    Set<T> argumentSet = evaluator.eval(args.get(0).getExpression(), env);

    int depthBound = args.size() >= 2 ? args.get(1).getInteger() : Integer.MAX_VALUE;
    Set<T> result = new LinkedHashSet<>(argumentSet);
    Set<T> current = argumentSet;

    for (int i = 0; i < depthBound; i++) {
      Set<T> next = new LinkedHashSet<>();
      Consumer<? super T> consumer =
          queryTarget -> {
            boolean added = result.add(queryTarget);
            if (added) {
              next.add(queryTarget);
            }
          };
      env.getFirstOrderClasspath(current).forEach(consumer);
      if (next.isEmpty()) {
        break;
      }
      current = next;
    }
    return env.restrictToInstancesOf(result, HasClasspathEntries.class)
        .collect(ImmutableSet.toImmutableSet());
  }
}
