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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.immutables.value.Value;

/** A query expression for user-defined query functions. */
@Value.Immutable(prehash = true, builder = false, copy = false)
@BuckStyleValue
public abstract class FunctionExpression<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  @Value.Auxiliary
  public abstract QueryFunction<?, NODE_TYPE> getFunction();

  // Use the function's class for equals/hashcode.
  @Value.Derived
  Class<?> getFunctionClass() {
    return getFunction().getClass();
  }

  public abstract ImmutableList<Argument<NODE_TYPE>> getArgs();

  @Override
  @SuppressWarnings("unchecked")
  <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
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
  public String toString() {
    return getFunction().getName()
        + "("
        + Joiner.on(", ").join(Iterables.transform(getArgs(), Object::toString))
        + ")";
  }
}
