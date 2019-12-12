/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * A lightweight wrapper over a {@link ConcurrentMap} that imitates the bits of the public API of
 * {@link com.google.common.cache.Cache} that's used by the {@link Parser}.
 */
class ConcurrentMapCache<K, V> {

  /** Resizing is expensive. This saves us four resizes from the normal default of 16. */
  static final int DEFAULT_INITIAL_CAPACITY = 256;

  /** Taken from {@link ConcurrentMap}. */
  static final float DEFAULT_LOAD_FACTOR = 0.75f;

  private final ConcurrentMap<K, V> values;

  public ConcurrentMapCache(int numThreads) {
    this.values =
        new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, numThreads);
  }

  public V putIfAbsentAndGet(K key, V newValue) {
    V seen = values.putIfAbsent(key, newValue);
    return seen == null ? newValue : seen;
  }

  @Nullable
  public V getIfPresent(K key) {
    return values.get(key);
  }

  public void invalidateAll(Set<K> keys) {
    for (K key : keys) {
      invalidate(key);
    }
  }

  public void invalidate(K key) {
    values.remove(key);
  }

  public Set<K> keySet() {
    return this.values.keySet();
  }

  public Collection<V> values() {
    return this.values.values();
  }
}
