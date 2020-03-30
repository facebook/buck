/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.query;

import com.facebook.buck.core.model.QueryTarget;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import java.util.Objects;
import java.util.Set;

/** A set(word, ..., word) expression or literal set of targets precomputed at parse-time. */
final class TargetSetExpression<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  private final Set<NODE_TYPE> targets;
  private final int hash;

  @SuppressWarnings({"unchecked", "rawtypes"})
  static <NODE_TYPE> TargetSetExpression<NODE_TYPE> of(Set<? extends QueryTarget> targets) {
    return new TargetSetExpression(targets);
  }

  private TargetSetExpression(Set<NODE_TYPE> targets) {
    this.targets = targets;
    this.hash = Objects.hash(targets);
  }

  Set<NODE_TYPE> getTargets() {
    return targets;
  }

  @Override
  @SuppressWarnings("unchecked")
  <OUTPUT_TYPE extends QueryTarget> Set<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) {
    return (Set<OUTPUT_TYPE>) targets;
  }

  @Override
  public void traverse(Visitor<NODE_TYPE> visitor) {
    visitor.visit(this);
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

    if (!(obj instanceof TargetSetExpression)) {
      return false;
    }

    TargetSetExpression<?> that = (TargetSetExpression<?>) obj;
    return Objects.equals(this.targets, that.targets);
  }

  @Override
  public String toString() {
    if (targets.size() == 1) {
      return Iterables.getOnlyElement(targets).toString();
    }

    return "set(" + Joiner.on(' ').join(targets) + ")";
  }
}
