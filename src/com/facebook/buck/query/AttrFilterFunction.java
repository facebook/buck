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
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.string.StringMatcher;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A attrfilter(attribute, value, argument) filter expression, which computes the subset of nodes in
 * 'argument' whose 'attribute' contains the given value.
 *
 * <pre>expr ::= ATTRFILTER '(' WORD ',' WORD ',' expr ')'</pre>
 */
public class AttrFilterFunction<NODE_TYPE> implements QueryFunction<NODE_TYPE> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.WORD, ArgumentType.EXPRESSION);

  public AttrFilterFunction() {}

  @Override
  public String getName() {
    return "attrfilter";
  }

  @Override
  public int getMandatoryArguments() {
    return 3;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  public Set<NODE_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator,
      QueryEnvironment<NODE_TYPE> env,
      ImmutableList<Argument<NODE_TYPE>> args)
      throws QueryException {
    QueryExpression<NODE_TYPE> argument = args.get(args.size() - 1).getExpression();
    ParamName attr = ParamName.bySnakeCase(args.get(0).getWord());

    String attrValue = args.get(1).getWord();
    Predicate<Object> predicate =
        input -> {
          if (input instanceof Collection) {
            Collection<?> collection = (Collection<?>) input;
            for (Object item : collection) {
              if (matches(item, attrValue)) {
                return true;
              }
            }
          }

          return matches(input, attrValue);
        };

    Set<NODE_TYPE> targets = evaluator.eval(argument, env);
    HashSet<NODE_TYPE> result = new HashSet<>(targets.size());
    for (NODE_TYPE target : targets) {
      Set<Object> matchingObjects = env.filterAttributeContents(target, attr, predicate);
      if (!matchingObjects.isEmpty()) {
        result.add(target);
      }
    }
    return result;
  }

  private boolean matches(Object value, String expectedValue) {
    if (value instanceof StringMatcher) {
      return ((StringMatcher) value).matches(expectedValue);
    }
    if (value instanceof Collection || value instanceof Map) {
      return false;
    }
    return expectedValue.equals(value.toString());
  }
}
