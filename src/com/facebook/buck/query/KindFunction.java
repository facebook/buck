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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.google.common.collect.ImmutableList;

/**
 * A kind(pattern, argument) filter expression, which computes the subset
 * of nodes in 'argument' whose kind matches the unanchored regex 'pattern'.
 *
 * <pre>expr ::= KIND '(' WORD ',' expr ')'</pre>
 */
public class KindFunction extends RegexFilterFunction {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  public KindFunction() {
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
  public ImmutableList<ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  protected QueryExpression getExpressionToEval(ImmutableList<Argument> args) {
    return args.get(1).getExpression();
  }

  @Override
  protected String getPattern(ImmutableList<Argument> args) {
    return args.get(0).getWord();
  }

  @Override
  protected <T> String getStringToFilter(
      QueryEnvironment<T> env,
      ImmutableList<Argument> args,
      T target)
      throws QueryException, InterruptedException {
    return env.getTargetKind(target);
  }
}
