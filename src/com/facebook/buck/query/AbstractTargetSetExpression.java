/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/** A set(word, ..., word) expression or literal set of targets precomputed at parse-time. */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractTargetSetExpression extends QueryExpression {
  abstract ImmutableSet<QueryTarget> getTargets();

  @Override
  ImmutableSet<QueryTarget> eval(QueryEvaluator evaluator, QueryEnvironment env) {
    return getTargets();
  }

  @Override
  public void traverse(Visitor visitor) {
    visitor.visit(this);
  }

  @Override
  public String toString() {
    if (getTargets().size() == 1) {
      return getTargets().asList().get(0).toString();
    }

    return "set(" + Joiner.on(' ').join(getTargets()) + ")";
  }
}
