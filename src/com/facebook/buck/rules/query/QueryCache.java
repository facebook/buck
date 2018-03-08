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

package com.facebook.buck.rules.query;

import com.facebook.buck.query.CachingQueryEvaluator;
import com.facebook.buck.query.QueryEvaluator;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** Cache that evaluates and stores the result of a dependency {@link Query}. */
public class QueryCache {
  private final LoadingCache<TargetGraph, CachingQueryEvaluator> evaluators;

  public QueryCache() {
    evaluators = CacheBuilder.newBuilder().build(CacheLoader.from(CachingQueryEvaluator::new));
  }

  QueryEvaluator getQueryEvaluator(TargetGraph targetGraph) {
    try {
      return evaluators.get(targetGraph);
    } catch (ExecutionException e) {
      throw new RuntimeException("Failed to obtain query evaluator", e);
    }
  }

  @VisibleForTesting
  boolean isPresent(TargetGraph targetGraph, GraphEnhancementQueryEnvironment env, Query query)
      throws QueryException {
    CachingQueryEvaluator evaluator = evaluators.getIfPresent(targetGraph);
    return Objects.nonNull(evaluator)
        && evaluator.isPresent(QueryExpression.parse(query.getQuery(), env));
  }
}
