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

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An abstract class that provides generic regex filter functionality. The actual expressions to
 * filter are defined in the subclasses.
 */
abstract class RegexFilterFunction implements QueryFunction {

  protected abstract QueryExpression getExpressionToEval(ImmutableList<Argument> args);

  protected abstract String getPattern(ImmutableList<Argument> args);

  protected abstract String getStringToFilter(
      QueryEnvironment env, ImmutableList<Argument> args, QueryTarget target)
      throws QueryException, InterruptedException;

  @Override
  public ImmutableSet<QueryTarget> eval(
      QueryEnvironment env, ImmutableList<Argument> args, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    Pattern compiledPattern;
    try {
      compiledPattern = Pattern.compile(getPattern(args));
    } catch (IllegalArgumentException e) {
      throw new QueryException(
          String.format("Illegal pattern regexp '%s': %s", getPattern(args), e.getMessage()));
    }

    Set<QueryTarget> targets = getExpressionToEval(args).eval(env, executor);
    ImmutableSet.Builder<QueryTarget> result = new ImmutableSet.Builder<>();
    for (QueryTarget target : targets) {
      String attributeValue = getStringToFilter(env, args, target);
      if (compiledPattern.matcher(attributeValue).find()) {
        result.add(target);
      }
    }
    return result.build();
  }
}
