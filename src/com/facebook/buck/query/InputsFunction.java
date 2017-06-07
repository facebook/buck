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

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Set;

/**
 * An 'inputs(x)' expression, which finds the direct input files of the given argument set 'x'.
 *
 * <pre>expr ::= INPUTS '(' expr ')'</pre>
 */
public class InputsFunction implements QueryFunction {

  @Override
  public String getName() {
    return "inputs";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ImmutableList.of(ArgumentType.EXPRESSION);
  }

  /** Evaluates to the direct inputs of the argument. */
  @Override
  public ImmutableSet<QueryTarget> eval(
      QueryEnvironment env, ImmutableList<Argument> args, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    Set<QueryTarget> argumentSet = args.get(0).getExpression().eval(env, executor);
    env.buildTransitiveClosure(argumentSet, 0, executor);

    ImmutableSet.Builder<QueryTarget> result = new ImmutableSet.Builder<>();

    for (QueryTarget target : argumentSet) {
      result.addAll(env.getInputs(target));
    }
    return result.build();
  }
}
