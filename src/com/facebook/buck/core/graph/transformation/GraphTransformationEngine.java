/*
 * Copyright 2018-present Facebook, Inc.
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

import com.google.common.collect.ImmutableMap;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Transformation engine that transforms supplied ComputeKey into ComputeResult via {@link
 * GraphTransformer}. This engine is able to asynchronously run graph based computation, reusing
 * results when possible. Note that the computation graph must be an acyclic graph.
 *
 * <p>This engine is able to deal with dependencies in the computation graph by having {@link
 * GraphTransformer} request dependent results of other transformations through {@link
 * GraphTransformer#discoverPreliminaryDeps(ComputeKey)} and {@link
 * GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)}
 */
public interface GraphTransformationEngine extends AutoCloseable {

  /** Shuts down the engine and the backing executor */
  @Override
  void close();

  /**
   * Asynchronously computes the result for the given key
   *
   * @param key the specific Key on the graph to compute
   * @return future of the result of applying the transformer on the graph with the given key
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      Future<ResultType> compute(KeyType key);

  /**
   * Synchronously computes the given key
   *
   * @param key the specific Key on the graph to compute
   * @return the result of applying the transformer on the graph with the given key
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ResultType computeUnchecked(KeyType key);

  /**
   * Asynchronously computes the result for multiple keys
   *
   * @param keys iterable of keys to compute on the graph
   * @return a map of futures of the result for each of the keys supplied
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ImmutableMap<KeyType, Future<ResultType>> computeAll(Set<KeyType> keys);

  /**
   * Synchronously computes the result for multiple keys
   *
   * @param keys iterable of the keys to compute on the graph
   * @return a map of the results for each of the keys supplied
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ImmutableMap<KeyType, ResultType> computeAllUnchecked(Set<KeyType> keys);
}
