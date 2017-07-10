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
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Objects;
import org.immutables.value.Value;

/** A query expression for user-defined query functions. */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractFunctionExpression extends QueryExpression {
  abstract QueryFunction getFunction();

  abstract ImmutableList<Argument> getArgs();

  @Override
  ImmutableSet<QueryTarget> eval(QueryEvaluator evaluator, QueryEnvironment env)
      throws QueryException {
    return getFunction().eval(evaluator, env, getArgs());
  }

  @Override
  public void traverse(QueryExpression.Visitor visitor) {
    if (visitor.visit(this) == VisitResult.CONTINUE) {
      for (Argument arg : getArgs()) {
        if (arg.getType() == ArgumentType.EXPRESSION) {
          arg.getExpression().traverse(visitor);
        }
      }
    }
  }

  @Override
  public String toString() {
    return getFunction().getName()
        + "("
        + Joiner.on(", ").join(Iterables.transform(getArgs(), Object::toString))
        + ")";
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof FunctionExpression) && equalTo((FunctionExpression) other);
  }

  private boolean equalTo(FunctionExpression other) {
    return getFunction().getClass().equals(other.getFunction().getClass())
        && getArgs().equals(other.getArgs());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getFunction().getClass(), getArgs());
  }
}
