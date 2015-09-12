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

import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.google.common.collect.ImmutableList;
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
 * A "testsof" query expression, which computes the tests of the given targets.
 *
 * This operator behavior is documented at docs/command/query.soy
 *
 * <pre>expr ::= TESTSOF '(' expr ')'</pre>
 */
public class QueryTestsOfFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.EXPRESSION);

  public QueryTestsOfFunction() {
  }

  @Override
  public String getName() {
    return "testsof";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    // This function is specific to Buck.
    // Casts are made in order to keep Bazel's structure for evaluating queries unchanged.
    if (!(env instanceof BuckQueryEnvironment)) {
      throw new QueryException("The environment should be an instance of BuckQueryEnvironment");
    }
    BuckQueryEnvironment buckEnv = (BuckQueryEnvironment) env;
    Set<T> targets = args.get(0).getExpression().eval(env);
    Set<T> tests = new LinkedHashSet<>();
    for (T target : targets) {
      TargetNode<?> node = buckEnv.getNode((QueryTarget) target);
      tests.addAll(
          (Collection<T>) buckEnv.getTargetsFromBuildTargetsContainer(
              TargetNodes.getTestTargetsForNode(node)));
    }
    return tests;
  }
}
