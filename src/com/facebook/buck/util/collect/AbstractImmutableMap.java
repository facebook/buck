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

package com.facebook.buck.util.collect;

import java.util.Map;

/**
 * Base class for immutable map implementations.
 *
 * <p>This does not extend {@link java.util.AbstractMap} because it adds unnecessary memory
 * overhead.
 */
abstract class AbstractImmutableMap<K, V> implements Map<K, V> {

  @Override
  public V put(K key, V value) {
    throw new UnsupportedOperationException("immutable map");
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    throw new UnsupportedOperationException("immutable map");
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException("immutable map");
  }

  @Override
  public V remove(Object key) {
    throw new UnsupportedOperationException("immutable map");
  }

  @Override
  public boolean remove(Object key, Object value) {
    throw new UnsupportedOperationException("immutable map");
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Map<?, ?>)) {
      return false;
    }
    return entrySet().equals(((Map<?, ?>) obj).entrySet());
  }

  @Override
  public int hashCode() {
    int hash = 0;
    for (Entry<K, V> entry : entrySet()) {
      hash += entry.hashCode();
    }
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (Entry<K, V> entry : entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(entry.getKey());
      sb.append("=");
      sb.append(entry.getValue());
    }
    sb.append("}");
    return sb.toString();
  }
}
