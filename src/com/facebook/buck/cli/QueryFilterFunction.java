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
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.List;

/**
 * A filter(pattern, argument) expression, evaluates its argument and filters
 * the resulting targets by applying the given regular expression pattern to
 * the targets' names.
 *
 * <pre>expr ::= FILTER '(' WORD ',' expr ')'</pre>
 */
public class QueryFilterFunction extends QueryRegexFilterFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  QueryFilterFunction() {
  }

  @Override
  public String getName() {
    return "filter";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  protected QueryExpression getExpressionToEval(List<Argument> args) {
    return args.get(1).getExpression();
  }

  @Override
  protected String getPattern(List<Argument> args) {
    return args.get(0).getWord();
  }

  @Override
  protected <T> String getStringToFilter(QueryEnvironment<T> env, List<Argument> args, T target) {
    return target.toString();
  }
}
