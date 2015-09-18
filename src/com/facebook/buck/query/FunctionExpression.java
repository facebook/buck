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
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.Set;

/**
 * A query expression for user-defined query functions.
 */
public class FunctionExpression extends QueryExpression {
  QueryFunction function;
  ImmutableList<Argument> args;

  public FunctionExpression(QueryFunction function, ImmutableList<Argument> args) {
    this.function = function;
    this.args = args;
  }

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env) throws QueryException, InterruptedException {
    return function.<T>eval(env, args);
  }

  @Override
  public void collectTargetPatterns(Collection<String> literals) {
    for (Argument arg : args) {
      if (arg.getType() == ArgumentType.EXPRESSION) {
        arg.getExpression().collectTargetPatterns(literals);
      }
    }
  }

  @Override
  public String toString() {
    return function.getName() +
        "(" + Joiner.on(", ").join(Iterables.transform(args, Functions.toStringFunction())) + ")";
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof FunctionExpression) && equalTo((FunctionExpression) other);
  }

  private boolean equalTo(FunctionExpression other) {
    return function.getClass().equals(other.function.getClass()) && args.equals(other.args);
  }

  @Override
  public int hashCode() {
    int h = 31;
    h = h * 17 + function.hashCode();
    h = h * 17 + args.hashCode();
    return h;
  }
}
