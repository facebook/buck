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

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSet;

/**
 * Interface for transformations with the {@link GraphTransformationEngine}.
 *
 * <p>The transformation is guaranteed the following conditions:
 *
 * <ul>
 *   <li>1. {@link #transform(ComputeKey, ComputationEnvironment)} is only called once per key if
 *       caching is enabled.
 *   <li>2. {@link #transform(ComputeKey, ComputationEnvironment)} is only called after all keys in
 *       {@link #discoverDeps(ComputeKey, ComputationEnvironment)} has been computed.
 *   <li>3. {@link #discoverDeps(ComputeKey, ComputationEnvironment)} is only called after all keys
 *       in {@link #discoverPreliminaryDeps(ComputeKey)} has been computed
 * </ul>
 *
 * Note that dependencies can be keys of other {@link GraphComputation}s.
 *
 * @param <Key> The types of Keys used to query for the result on the graph computation
 * @param <Result> The result of the computation given a specific key.
 */
public interface GraphComputation<Key extends ComputeKey<Result>, Result extends ComputeResult> {

  /**
   * @return the class of the key for this transformation. This should match {@link
   *     ComputeKey#getKeyClass()}
   */
  Class<Key> getKeyClass();

  /**
   * Perform a transformation identified by key {@link Key} into a final type {@link Result}. This
   * transformation should be performed synchronously.
   *
   * @param key The Key of the requested result
   * @param env The execution environment containing results of keys from {@link
   *     #discoverDeps(ComputeKey, ComputationEnvironment)} and {@link
   *     #discoverPreliminaryDeps(ComputeKey)}
   * @return The result of the transformation
   */
  Result transform(Key key, ComputationEnvironment env) throws Exception;

  /**
   * Compute dependent keys required to compute given key, and a set of dependencies as listed by
   * {@link #discoverPreliminaryDeps(ComputeKey)}. The results of those computations will be
   * available in {@link #transform(ComputeKey, ComputationEnvironment)} as a part of {@link
   * ComputationEnvironment}
   *
   * @param key the current key to transform
   * @param env The execution environment containing results of keys from {@link
   *     #discoverPreliminaryDeps(ComputeKey)}
   * @return a set of keys that the transformation of the current key depends on
   */
  ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      Key key, ComputationEnvironment env) throws Exception;

  /**
   * Compute dependent keys required to compute given the current key. The results of those
   * computations will be available in {@link #discoverDeps(ComputeKey, ComputationEnvironment)} as
   * a part of {@link ComputationEnvironment}, and {@link #transform(ComputeKey,
   * ComputationEnvironment)}
   *
   * @param key the current key to transform
   * @return a set of keys that the {@link #discoverDeps(ComputeKey, ComputationEnvironment)} and
   *     {@link #transform(ComputeKey, ComputationEnvironment)} of the current key depends on
   */
  ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(Key key)
      throws Exception;
}
