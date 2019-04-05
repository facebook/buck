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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * A "owner" query expression, which computes the rules that own the given files.
 *
 * <pre>expr ::= OWNER '(' WORD ')'</pre>
 */
public class OwnerFunction<ENV_NODE_TYPE extends QueryTarget>
    implements QueryFunction<ENV_NODE_TYPE, ENV_NODE_TYPE> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD);

  public OwnerFunction() {}

  @Override
  public String getName() {
    return "owner";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  public ImmutableSet<ENV_NODE_TYPE> eval(
      QueryEvaluator<ENV_NODE_TYPE> evaluator,
      QueryEnvironment<ENV_NODE_TYPE> env,
      ImmutableList<Argument<ENV_NODE_TYPE>> args)
      throws QueryException {
    return env.getFileOwners(ImmutableList.of(args.get(0).getWord()));
  }
}
