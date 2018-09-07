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

import java.util.concurrent.CompletionStage;

/**
 * Functional interface for transformations with the {@link GraphTransformationEngine}.
 *
 * @param <Key> The types of Keys used to query for the result on the graph computation
 * @param <Result> The result of the computation given a specific key
 */
@FunctionalInterface
public interface GraphTransformer<Key, Result> {

  /**
   * @param key The Key of the requested result
   * @param env The execution environment to request dependencies
   * @return a future of the result requested
   */
  CompletionStage<Result> transform(Key key, TransformationEnvironment<Key, Result> env);
}
