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
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import java.util.Set;

/**
 * A labels(label, argument) expression, which returns the targets in the attribute 'label' of the
 * targets evaluated in the argument
 *
 * <pre>expr ::= LABELS '(' WORD ',' expr ')'</pre>
 */
public class LabelsFunction<NODE_TYPE> implements QueryFunction<NODE_TYPE> {

  private static final ImmutableList<ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(ArgumentType.WORD, ArgumentType.EXPRESSION);

  public LabelsFunction() {}

  @Override
  public String getName() {
    return "labels";
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
  public Set<NODE_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator,
      QueryEnvironment<NODE_TYPE> env,
      ImmutableList<Argument<NODE_TYPE>> args)
      throws QueryException {
    ParamName label = ParamName.bySnakeCase(args.get(0).getWord());
    Set<NODE_TYPE> inputs = evaluator.eval(args.get(1).getExpression(), env);
    return Unions.of((NODE_TYPE input) -> env.getTargetsInAttribute(input, label), inputs);
  }
}
