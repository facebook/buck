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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * {@link ImmutableMap} uses 16 fewer bytes per entry than {@link java.util.TreeMap}, but does not
 * allow null values. This wrapper class lets us have our cake and eat it too -- we use a sentinel
 * object in the underlying {@link ImmutableMap} and translate it on any read path.
 */
public final class ImmutableMapWithNullValues<K, V> extends AbstractMap<K, V> {
  private static final Object NULL = new Object();
  private final Map<K, Object> delegate;

  private final int hashCode;

  private ImmutableMapWithNullValues(Map<K, Object> delegate) {
    this.delegate = delegate;
    this.hashCode = computeHashCode();
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public V get(@Nullable Object key) {
    Object result = delegate.get(key);
    if (result == NULL) {
      return null;
    }
    return (V) result;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Collection<V> values() {
    return delegate
        .values()
        .stream()
        .map(v -> v == NULL ? null : (V) v)
        .collect(Collectors.toList());
  }

  @Override
  @SuppressWarnings("unchecked")
  public Set<Entry<K, V>> entrySet() {
    return delegate
        .entrySet()
        .stream()
        .map(
            e ->
                e.getValue() == NULL
                    ? new AbstractMap.SimpleEntry<K, V>(e.getKey(), null)
                    : (Map.Entry<K, V>) e)
        // Use ImmutableSet instead of Set here to preserve iteration order:
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Map)) {
      return false;
    }
    if (this.hashCode != other.hashCode()) {
      return false;
    }
    return entrySet().equals(((Map<K, V>) other).entrySet());
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  private int computeHashCode() {
    return entrySet().hashCode();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("{");
    Joiner.on(", ").useForNull("null").withKeyValueSeparator("=").appendTo(builder, this);
    builder.append("}");
    return builder.toString();
  }

  public static class Builder<K, V> {
    private final ImmutableMap.Builder<K, Object> builder;

    public static <K, V> Builder<K, V> insertionOrder() {
      return new Builder<>(new ImmutableMap.Builder<K, Object>());
    }

    public static <K extends Comparable<?>, V> Builder<K, V> sorted() {
      return new Builder<>(new ImmutableSortedMap.Builder<>(Ordering.natural()));
    }

    private Builder(ImmutableMap.Builder<K, Object> builder) {
      this.builder = builder;
    }

    public Builder<K, V> put(K key, @Nullable V value) {
      builder.put(key, value == null ? NULL : value);
      return this;
    }

    public ImmutableMapWithNullValues<K, V> build() {
      return new ImmutableMapWithNullValues<K, V>(builder.build());
    }
  }
}
