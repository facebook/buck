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

import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.Objects;
import java.util.Set;

/** A query expression for user-defined query functions. */
final class FunctionExpression<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  private final QueryEnvironment.QueryFunction<?, NODE_TYPE> function;
  private final ImmutableList<QueryEnvironment.Argument<NODE_TYPE>> args;
  private final int hash;

  public FunctionExpression(
      QueryEnvironment.QueryFunction<?, NODE_TYPE> function,
      Iterable<? extends QueryEnvironment.Argument<NODE_TYPE>> args) {
    this.function = function;
    this.args = ImmutableList.copyOf(args);
    this.hash = Objects.hash(function.getClass(), args);
  }

  QueryFunction<?, NODE_TYPE> getFunction() {
    return function;
  }

  ImmutableList<Argument<NODE_TYPE>> getArgs() {
    return args;
  }

  @Override
  @SuppressWarnings("unchecked")
  <OUTPUT_TYPE extends QueryTarget> Set<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) throws QueryException {
    return ((QueryFunction<OUTPUT_TYPE, NODE_TYPE>) getFunction()).eval(evaluator, env, getArgs());
  }

  @Override
  public void traverse(QueryExpression.Visitor<NODE_TYPE> visitor) {
    if (visitor.visit(this) == VisitResult.CONTINUE) {
      for (Argument<NODE_TYPE> arg : getArgs()) {
        if (arg.getType() == ArgumentType.EXPRESSION) {
          arg.getExpression().traverse(visitor);
        }
      }
    }
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof FunctionExpression)) {
      return false;
    }

    FunctionExpression<?> that = (FunctionExpression<?>) obj;
    return Objects.equals(this.function.getClass(), that.function.getClass())
        && Objects.equals(this.args, that.args);
  }

  @Override
  public String toString() {
    return getFunction().getName()
        + "("
        + Joiner.on(", ").join(Iterables.transform(getArgs(), Object::toString))
        + ")";
  }
}
