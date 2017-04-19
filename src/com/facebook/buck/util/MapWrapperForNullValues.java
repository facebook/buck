/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingMapEntry;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

/**
 * {@link ImmutableMap} uses 16 fewer bytes per entry than {@link TreeMap}, but does not allow
 * null values. This wrapper class lets us have our cake and eat it too -- we use a sentinel
 * object in the underlying {@link ImmutableMap} and translate it on any read path.
 */
public final class MapWrapperForNullValues<K, V> extends ForwardingMap<K, V> {
  public static final Object NULL = new Object();
  private final Map<K, V> delegate;

  public MapWrapperForNullValues(Map<K, V> delegate) {
    this.delegate = delegate;
  }

  @Override
  protected Map<K, V> delegate() {
    return delegate;
  }

  @Override
  @Nullable
  public V get(@Nullable Object key) {
    V result = super.get(key);
    if (result == NULL) {
      return null;
    }
    return result;
  }

  @Override
  public Collection<V> values() {
    return super.values().stream()
        .map(v -> v == NULL ? null : v)
        .collect(Collectors.toList());
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return super.entrySet().stream()
        .map(e -> e.getValue() == NULL
            ? new EntryWrapperForNullValues<>(e)
            : e)
        // Use ImmutableSet instead of Set here to preserve iteration order:
        .collect(MoreCollectors.toImmutableSet());
  }

  // Note the warning here:
  //
  // https://google.github.io/guava/releases/19.0/api/docs/com/google/common/collect/ForwardingMap.html
  //
  // In particular, overriding `entrySet()` means we need to
  // override `equals()`, `hashCode()`, and `toString()`.
  //
  // (We don't need to override `putAll()`, because this is
  // immutable.)
  @Override
  public boolean equals(Object other) {
    return standardEquals(other);
  }

  @Override
  public int hashCode() {
    return standardHashCode();
  }

  @Override
  public String toString() {
    return standardToString();
  }

  private static class EntryWrapperForNullValues<K, V> extends ForwardingMapEntry<K, V> {
    private final Map.Entry<K, V> delegate;

    public EntryWrapperForNullValues(Map.Entry<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected Entry<K, V> delegate() {
      return delegate;
    }

    @Override
    @Nullable
    public V getValue() {
      V result = super.getValue();
      if (result == NULL) {
        return null;
      }
      return result;
    }

    // Note the warning here:
    //
    // https://google.github.io/guava/releases/21.0/api/docs/com/google/common/collect/ForwardingMapEntry.html
    //
    // In particular, overriding `getValue()` means we need to
    // override `equals()`, `hashCode()`, and `toString()`,
    @Override
    public boolean equals(Object other) {
      return standardEquals(other);
    }

    @Override
    public int hashCode() {
      return standardHashCode();
    }

    @Override
    public String toString() {
      return standardToString();
    }
  }
}
