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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class CachingQueryEvaluator<ENV_NODE_TYPE> implements QueryEvaluator<ENV_NODE_TYPE> {
  private final Cache<QueryExpression<?>, ImmutableSet<?>> cache;

  public CachingQueryEvaluator() {
    this.cache = CacheBuilder.newBuilder().build();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
      QueryExpression<ENV_NODE_TYPE> exp, QueryEnvironment<ENV_NODE_TYPE> env)
      throws QueryException {
    try {
      return (ImmutableSet<OUTPUT_TYPE>) cache.get(exp, () -> exp.eval(this, env));
    } catch (ExecutionException e) {
      throw new QueryException(e, "Failed executing query [%s]", exp);
    }
  }

  @VisibleForTesting
  public boolean isPresent(QueryExpression<?> exp) {
    return Objects.nonNull(cache.getIfPresent(exp));
  }
}
