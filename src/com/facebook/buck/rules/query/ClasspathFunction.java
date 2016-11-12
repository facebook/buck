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
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.Set;

/**
 * A classpath(expression) expression that calculates targets in the classpath of the
 * given library or libraries.
 * <pre>expr ::= CLASSPATH '(' expr ')'</pre>
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
    return ImmutableList.of(QueryEnvironment.ArgumentType.EXPRESSION);
  }

  @Override
  public Set<QueryTarget> eval(
      QueryEnvironment env,
      ImmutableList<QueryEnvironment.Argument> args,
      ListeningExecutorService executor) throws QueryException, InterruptedException {
    Preconditions.checkArgument(env instanceof GraphEnhancementQueryEnvironment);
    Set<QueryTarget> targets = args.get(0).getExpression().eval(env, executor);
    return ((GraphEnhancementQueryEnvironment) env).getClasspath(targets);
  }
}
