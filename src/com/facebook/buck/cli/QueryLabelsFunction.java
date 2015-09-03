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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.List;
import java.util.Set;

/**
 * A labels(label, argument) expression, which returns the targets in the
 * attribute 'label' of the targets evaluated in the argument
 *
 * <pre>expr ::= LABELS '(' WORD ',' expr ')'</pre>
 */
public class QueryLabelsFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  public QueryLabelsFunction() {
  }

  @Override
  public String getName() {
    return "labels";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> ImmutableSet<T> eval(
      QueryEnvironment<T> env,
      QueryExpression expression,
      List<Argument> args)
      throws QueryException, InterruptedException {
    // Casts are made in order to keep Bazel's structure for evaluating queries unchanged.
    if (!(env instanceof BuckQueryEnvironment)) {
      throw new QueryException("The environment should be an instance of BuckQueryEnvironment");
    }
    BuckQueryEnvironment buckEnv = (BuckQueryEnvironment) env;
    Set<T> inputs = args.get(1).getExpression().eval(env);
    String label = args.get(0).getWord();
    ImmutableSortedSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (T input : inputs) {
      builder.addAll(buckEnv.getAttributeValue((QueryTarget) input, label));
    }
    return (ImmutableSet<T>) builder.build();
  }
}
