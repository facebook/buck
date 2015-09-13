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

import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An abstract class that provides generic regex filter functionality.
 * The actual expressions to filter are defined in the subclasses.
 */
abstract class QueryRegexFilterFunction implements QueryFunction {

  protected abstract QueryExpression getExpressionToEval(List<Argument> args);

  protected abstract String getPattern(List<Argument> args);

  protected abstract <T> String getStringToFilter(
      QueryEnvironment<T> env,
      List<Argument> args,
      T target)
      throws QueryException, InterruptedException;

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    Pattern compiledPattern;
    try {
      compiledPattern = Pattern.compile(getPattern(args));
    } catch (IllegalArgumentException e) {
      throw new QueryException(
          expression,
          String.format("Illegal pattern regexp '%s': %s", getPattern(args), e.getMessage()));
    }

    Set<T> targets = getExpressionToEval(args).eval(env);
    env.buildTransitiveClosure(expression, targets, Integer.MAX_VALUE);
    Set<T> result = new LinkedHashSet<>();
    for (T target : targets) {
      String attributeValue = getStringToFilter(env, args, target);
      if (compiledPattern.matcher(attributeValue).find()) {
        result.add(target);
      }
    }
    return result;
  }
}
