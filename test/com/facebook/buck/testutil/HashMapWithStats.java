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

package com.facebook.buck.testutil;

import java.util.HashMap;
import java.util.Map;

/** HashMap with stats for operations */
public class HashMapWithStats<K, V> extends HashMap<K, V> {
  private Map<K, Integer> putsByKey = new HashMap<>();
  private Map<Object, Integer> getsByKey = new HashMap<>();

  public int numPutsForKey(K key) {
    return putsByKey.getOrDefault(key, 0);
  }

  public int numGetsForKey(K key) {
    return getsByKey.getOrDefault(key, 0);
  }

  public int numPuts() {
    return putsByKey.values().stream().reduce(0, Integer::sum);
  }

  public int numGets() {
    return getsByKey.values().stream().reduce(0, Integer::sum);
  }

  @Override
  public V put(K key, V value) {
    putsByKey.put(key, putsByKey.getOrDefault(key, 0) + 1);
    return super.put(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    m.entrySet().forEach(entry -> put(entry.getKey(), entry.getValue()));
  }

  @Override
  public V get(Object key) {
    getsByKey.put(key, getsByKey.getOrDefault(key, 0) + 1);
    return super.get(key);
  }

  @Override
  public V getOrDefault(Object key, V defaultValue) {
    getsByKey.put(key, getsByKey.getOrDefault(key, 0) + 1);
    return super.getOrDefault(key, defaultValue);
  }
}
