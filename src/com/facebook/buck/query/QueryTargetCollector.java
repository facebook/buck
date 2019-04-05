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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableSet;

class QueryTargetCollector<ENV_NODE_TYPE> implements QueryExpression.Visitor<ENV_NODE_TYPE> {
  private final ImmutableSet.Builder<QueryTarget> targets;
  private final QueryEnvironment<ENV_NODE_TYPE> env;

  QueryTargetCollector(QueryEnvironment<ENV_NODE_TYPE> env) {
    this.targets = ImmutableSet.builder();
    this.env = env;
  }

  @Override
  public QueryExpression.VisitResult visit(QueryExpression<ENV_NODE_TYPE> exp) {
    if (exp instanceof TargetLiteral) {
      try {
        targets.addAll(
            env.getTargetsMatchingPattern(((TargetLiteral<ENV_NODE_TYPE>) exp).getPattern()));
      } catch (QueryException e) {
        throw new HumanReadableException(e, "Error computing targets from literal [%s]", exp);
      }
    }

    if (exp instanceof TargetSetExpression) {
      targets.addAll(((AbstractTargetSetExpression<ENV_NODE_TYPE>) exp).getTargets());
    }

    return QueryExpression.VisitResult.CONTINUE;
  }

  ImmutableSet<QueryTarget> getTargets() {
    return targets.build();
  }
}
