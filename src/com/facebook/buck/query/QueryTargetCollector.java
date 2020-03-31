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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

class QueryTargetCollector<ENV_NODE_TYPE> implements QueryExpression.Visitor<ENV_NODE_TYPE> {
  private final ImmutableSet.Builder<ENV_NODE_TYPE> targets;
  private final QueryEnvironment.TargetEvaluator<ENV_NODE_TYPE> targetEvaluator;

  QueryTargetCollector(QueryEnvironment.TargetEvaluator<ENV_NODE_TYPE> targetEvaluator) {
    this.targets = ImmutableSet.builder();
    this.targetEvaluator = targetEvaluator;
  }

  @Override
  public QueryExpression.VisitResult visit(QueryExpression<ENV_NODE_TYPE> exp) {
    if (exp instanceof TargetLiteral) {
      try {
        targets.addAll(
            targetEvaluator.evaluateTarget(((TargetLiteral<ENV_NODE_TYPE>) exp).getPattern()));
      } catch (QueryException e) {
        throw new HumanReadableException(e, "Error computing targets from literal [%s]", exp);
      }
    }

    if (exp instanceof TargetSetExpression) {
      targets.addAll(((TargetSetExpression<ENV_NODE_TYPE>) exp).getTargets());
    }

    return QueryExpression.VisitResult.CONTINUE;
  }

  Set<ENV_NODE_TYPE> getTargets() {
    return targets.build();
  }
}
