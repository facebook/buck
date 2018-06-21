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
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A computation environment that {@link AsyncTransformer} can access. This class provides ability
 * of Transformers to request and execute their dependencies on the engine, without exposing
 * blocking operations.
 *
 * <p>The implementations of this environment should have all methods as tail recursive and
 * unblocking.
 */
public interface TransformationEnvironment<ComputeKey, ComputeResult> {

  /**
   * Method used for {@link AsyncTransformer} to get dependency results, and then construct the
   * {@link ComputeResult} for the current requested Key with the supplied asyncTransformation using
   * the dependency result.
   *
   * @param key The Key of the dependency to execute
   * @param asyncTransformation the async function to perform after dependency has completed
   * @return a Future of the result of applying {@code asyncTransformation} to the completed
   *     dependency result
   */
  CompletionStage<ComputeResult> evaluate(
      ComputeKey key, Function<ComputeResult, ComputeResult> asyncTransformation);

  /**
   * Method used for {@link AsyncTransformer} to get multiple dependency results, and then construct
   * the {@link ComputeResult} for the current requested Key with the supplied asyncTransformation
   * using the dependency result.
   *
   * @param keys The keys of the dependency to consume
   * @param asyncTransformation The function to run after all dependencies are completed, where the
   *     result of dependencies are given as a map of the keys to the result
   * @return a future of the result of applying {@code asyncTransformation} to the dependencies
   */
  CompletionStage<ComputeResult> evaluateAll(
      Iterable<ComputeKey> keys,
      Function<ImmutableMap<ComputeKey, ComputeResult>, ComputeResult> asyncTransformation);
}
