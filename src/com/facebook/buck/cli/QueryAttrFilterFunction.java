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

import com.google.common.base.CaseFormat;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A attrfilter(attribute, value, argument) filter expression, which computes the subset
 * of nodes in 'argument' whose 'attribute' contains the given value.
 *
 * <pre>expr ::= ATTRFILTER '(' WORD ',' WORD ',' expr ')'</pre>
 */
public class QueryAttrFilterFunction implements QueryFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.WORD, ArgumentType.EXPRESSION);

  QueryAttrFilterFunction() {
  }

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
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    // Casts are made in order to keep Bazel's structure for evaluating queries unchanged.
    if (!(env instanceof BuckQueryEnvironment)) {
      throw new QueryException("The environment should be an instance of BuckQueryEnvironment");
    }

    BuckQueryEnvironment buckEnv = (BuckQueryEnvironment) env;
    QueryExpression argument = args.get(args.size() - 1).getExpression();
    String attr = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, args.get(0).getWord());

    final String attrValue = args.get(1).getWord();
    final Predicate<Object> predicate = new Predicate<Object>() {
      @Override
      public boolean apply(Object input) {
        return attrValue.equals(input.toString());
      }
    };

    Set<T> result = new LinkedHashSet<>();
    for (T target : argument.eval(env)) {
      ImmutableSet<Object> matchingObjects =
          buckEnv.filterAttributeContents((QueryTarget) target, attr, predicate);
      if (!matchingObjects.isEmpty()) {
        result.add(target);
      }
    }
    return result;
  }

}
