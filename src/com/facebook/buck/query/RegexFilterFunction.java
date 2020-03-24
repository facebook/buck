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

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An abstract class that provides generic regex filter functionality. The actual expressions to
 * filter are defined in the subclasses.
 */
abstract class RegexFilterFunction<ENV_NODE_TYPE> implements QueryFunction<ENV_NODE_TYPE> {

  protected abstract QueryExpression<ENV_NODE_TYPE> getExpressionToEval(
      ImmutableList<Argument<ENV_NODE_TYPE>> args);

  protected abstract String getPattern(ImmutableList<Argument<ENV_NODE_TYPE>> args);

  protected abstract String getStringToFilter(
      QueryEnvironment<ENV_NODE_TYPE> env,
      ImmutableList<Argument<ENV_NODE_TYPE>> args,
      ENV_NODE_TYPE target)
      throws QueryException;

  @Override
  public Set<ENV_NODE_TYPE> eval(
      QueryEvaluator<ENV_NODE_TYPE> evaluator,
      QueryEnvironment<ENV_NODE_TYPE> env,
      ImmutableList<Argument<ENV_NODE_TYPE>> args)
      throws QueryException {
    Pattern compiledPattern;
    try {
      compiledPattern = Pattern.compile(getPattern(args));
    } catch (IllegalArgumentException e) {
      throw new QueryException(
          String.format("Illegal pattern regexp '%s': %s", getPattern(args), e.getMessage()));
    }

    Set<ENV_NODE_TYPE> targets = evaluator.eval(getExpressionToEval(args), env);
    HashSet<ENV_NODE_TYPE> result = new HashSet<>(targets.size());
    for (ENV_NODE_TYPE target : targets) {
      String attributeValue = getStringToFilter(env, args, target);
      if (compiledPattern.matcher(attributeValue).find()) {
        result.add(target);
      }
    }
    return result;
  }
}
