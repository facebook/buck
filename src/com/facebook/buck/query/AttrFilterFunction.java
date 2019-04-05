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
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A attrfilter(attribute, value, argument) filter expression, which computes the subset of nodes in
 * 'argument' whose 'attribute' contains the given value.
 *
 * <pre>expr ::= ATTRFILTER '(' WORD ',' WORD ',' expr ')'</pre>
 */
public class AttrFilterFunction implements QueryFunction<QueryBuildTarget, QueryBuildTarget> {

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
  public ImmutableSet<QueryBuildTarget> eval(
      QueryEvaluator<QueryBuildTarget> evaluator,
      QueryEnvironment<QueryBuildTarget> env,
      ImmutableList<Argument<QueryBuildTarget>> args)
      throws QueryException {
    QueryExpression<QueryBuildTarget> argument = args.get(args.size() - 1).getExpression();
    String attr = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, args.get(0).getWord());

    String attrValue = args.get(1).getWord();
    // filterAttributeContents() below will traverse the entire type hierarchy of each attr (see the
    // various type coercers). Collection types are (1) very common (2) expensive to convert to
    // string and (3) we shouldn't apply the filter to the stringified form, and so we have a fast
    // path to ignore them.
    Predicate<Object> predicate =
        input ->
            !(input instanceof Collection || input instanceof Map)
                && attrValue.equals(input.toString());

    ImmutableSet.Builder<QueryBuildTarget> result = new ImmutableSet.Builder<>();
    ImmutableSet<QueryBuildTarget> targets = evaluator.eval(argument, env);
    for (QueryBuildTarget target : targets) {
      ImmutableSet<Object> matchingObjects = env.filterAttributeContents(target, attr, predicate);
      if (!matchingObjects.isEmpty()) {
        result.add(target);
      }
    }
    return result.build();
  }
}
