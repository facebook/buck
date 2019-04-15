/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.graph.transformation;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents one "stage" of computation in the {@link GraphTransformationEngine}. This maps to a
 * single transformation by a {@link GraphComputation} from a {@link ComputeKey} to a {@link
 * ComputeResult}.
 *
 * @param <KeyType> the type of the {@link ComputeKey} for this stage
 * @param <ResultType> the type of the {@link ComputeResult} for this stage
 */
public class GraphComputationStage<
    KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult> {

  private final GraphComputation<KeyType, ResultType> transformer;
  private final GraphEngineCache<KeyType, ResultType> cache;

  public GraphComputationStage(GraphComputation<KeyType, ResultType> transformer) {
    this(
        transformer,
        new GraphEngineCache<KeyType, ResultType>() {
          private final ConcurrentHashMap<KeyType, ResultType> cache = new ConcurrentHashMap<>();

          @Override
          public Optional<ResultType> get(KeyType key) {
            return Optional.ofNullable(cache.get(key));
          }

          @Override
          public void put(KeyType key, ResultType resultType) {
            cache.put(key, resultType);
          }
        });
  }

  public GraphComputationStage(
      GraphComputation<KeyType, ResultType> transformer,
      GraphEngineCache<KeyType, ResultType> cache) {
    this.transformer = transformer;
    this.cache = cache;
  }

  GraphComputation<KeyType, ResultType> getTransformer() {
    return transformer;
  }

  Class<KeyType> getKeyClass() {
    return transformer.getKeyClass();
  }

  GraphEngineCache<KeyType, ResultType> getCache() {
    return cache;
  }

  ResultType transform(KeyType key, DefaultComputationEnvironment env) throws Exception {
    ResultType result = transformer.transform(key, env);

    cache.put(key, result);
    return result;
  }
}
