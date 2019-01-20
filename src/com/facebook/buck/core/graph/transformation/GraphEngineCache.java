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

import java.util.Optional;

/**
 * Interface for a Cache object that {@link DefaultGraphTransformationEngine} uses to store results
 * that have finished computing so that the results can be reused.
 *
 * <p>The cache should be thread safe
 *
 * @param <Key> Key to the cache
 * @param <Value> Value stored by Cache
 */
public interface GraphEngineCache<Key, Value> {

  /**
   * Optionally returns the cached result given the key
   *
   * @param key The desired key
   * @return the result if cached, otherwise an empty Optional
   */
  Optional<Value> get(Key key);

  /**
   * Offers the given key and value for caching
   *
   * @param key the key to cache
   * @param value the value to cache
   */
  void put(Key key, Value value);
}
