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
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A kind(pattern, argument) filter expression, which computes the set of subset
 * of nodes in 'argument' whose kind matches the unanchored regexp 'pattern'.
 *
 * <pre>expr ::= KIND '(' WORD ',' expr ')'</pre>
 */
public class QueryKindFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  QueryKindFunction() {
  }

  @Override
  public String getName() {
    return "kind";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public List<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    // Casts are made in order to keep Bazel's structure for evaluating queries unchanged.
    if (!(env instanceof BuckQueryEnvironment)) {
      throw new QueryException("The environment should be an instance of BuckQueryEnvironment");
    }

    Pattern compiledPattern;
    String pattern = args.get(0).getWord();
    try {
      compiledPattern = Pattern.compile(pattern);
    } catch (IllegalArgumentException e) {
      throw new QueryException(
          expression,
          String.format("Illegal pattern regexp '%s': %s", pattern, e.getMessage()));
    }

    BuckQueryEnvironment buckEnv = (BuckQueryEnvironment) env;
    QueryExpression argument = args.get(args.size() - 1).getExpression();

    Set<T> result = new LinkedHashSet<>();
    for (T target : argument.eval(env)) {
      if (compiledPattern.matcher(buckEnv.getTargetKind((QueryTarget) target)).find()) {
        result.add(target);
      }
    }
    return result;
  }
}
