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
package com.facebook.buck.core.graph.transformation.composition;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.google.common.collect.ImmutableSet;

/**
 * The function for composing {@link com.facebook.buck.core.graph.transformation.GraphComputation}s
 *
 * @param <Key1> the key type of the base computation
 * @param <Result1> the result type of the base computation
 */
@FunctionalInterface
public interface Composer<Key1, Result1> {

  /**
   * @param key the key from the base computation
   * @param result the result corresponding to the key of the base computation
   * @return the set of dependencies of the composed transformation given the key and result from
   *     the base computation
   * @throws Exception as appropriate when computing dependencies.
   */
  ImmutableSet<? extends ComputeKey<?>> transitionWith(Key1 key, Result1 result) throws Exception;
}
