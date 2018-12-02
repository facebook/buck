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

import java.util.Set;

/**
 * Interface for transformations with the {@link GraphTransformationEngine}.
 *
 * <p>The transformation is guaranteed the following conditions:
 *
 * <ul>
 *   <li>1. {@link #transform(Object, TransformationEnvironment)} is only called once per key if
 *       caching is enabled.
 *   <li>2. {@link #transform(Object, TransformationEnvironment)} is only called after all keys in
 *       {@link #discoverDeps(Object)} has been computed.
 * </ul>
 *
 * @param <Key> The types of Keys used to query for the result on the graph computation
 * @param <Result> The result of the computation given a specific key
 */
public interface GraphTransformer<Key, Result> {

  /**
   * @param key The Key of the requested result
   * @param env The execution environment containining results of keys from {@link
   *     #discoverDeps(Object)}
   * @return a future of the result requested
   */
  Result transform(Key key, TransformationEnvironment<Key, Result> env) throws Exception;

  /**
   * @param key the current key to transform
   * @return a set of keys that the transformation of the current key depends on
   */
  Set<Key> discoverDeps(Key key) throws Exception;
}
