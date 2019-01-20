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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
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
  private static final Object NULL =
      new Object() {
        @Override
        public int hashCode() {
          return 0;
        }

        @Override
        public boolean equals(Object o) {
          return o == this;
        }
      };

  private final Map<K, Object> delegate;

  private final int hashCode;

  @JsonCreator
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
  public Set<Entry<K, V>> entrySet() {
    return new EntrySetWithNullValues<>(this, delegate.entrySet());
  }

  private static class EntrySetWithNullValues<K, V> extends AbstractSet<Entry<K, V>> {

    private final ImmutableMapWithNullValues<K, V> map;
    private final Set<Entry<K, Object>> delegateSet;

    private EntrySetWithNullValues(
        ImmutableMapWithNullValues<K, V> map, Set<Entry<K, Object>> delegateSet) {
      this.map = map;
      this.delegateSet = delegateSet;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }

      Entry<K, Object> entry = (Entry<K, Object>) o;
      Object val = map.delegate.get(entry.getKey());
      if (val == null) {
        return false;
      }
      Object entryVal = entry.getValue() == null ? NULL : entry.getValue();
      return val.equals(entryVal);
    }

    class IteratorWithNullValues implements Iterator<Entry<K, V>> {

      private final Iterator<Entry<K, Object>> iteratorDelegate;

      IteratorWithNullValues(Iterator<Entry<K, Object>> iteratorDelegate) {
        this.iteratorDelegate = iteratorDelegate;
      }

      @Override
      public boolean hasNext() {
        return iteratorDelegate.hasNext();
      }

      @Override
      @SuppressWarnings("unchecked")
      public Entry<K, V> next() {
        Entry<K, Object> e = iteratorDelegate.next();
        return e.getValue() == NULL ? new SimpleEntry<>(e.getKey(), null) : (Entry<K, V>) e;
      }
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new IteratorWithNullValues(delegateSet.iterator());
    }

    @Override
    public int size() {
      return delegateSet.size();
    }
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
