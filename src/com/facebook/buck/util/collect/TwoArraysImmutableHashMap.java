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

import com.google.common.base.Preconditions;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import javax.annotation.Nullable;

/**
 * Open hash map implementation.
 *
 * <ul>
 *   <li>Ordered (order is specified by a builder)
 *   <li>Immutable
 *   <li>Null values allowed, but null keys are not
 *   <li>No "entries" allocated, space efficient
 * </ul>
 */
@SuppressWarnings("unchecked")
public class TwoArraysImmutableHashMap<K, V> extends AbstractImmutableMap<K, V> {
  private static final TwoArraysImmutableHashMap<?, ?> EMPTY =
      new TwoArraysImmutableHashMap<>(Hashtables.EMPTY_ARRAY, Hashtables.EMPTY_INT_ARRAY);

  // Hashtable, the table size is a power of two
  // Entries array is [K, V, K, V, ...]
  private final Object[] entries;

  // Indices of map entries; equal to map size
  private final int[] iterationOrder;

  private TwoArraysImmutableHashMap(Object[] entries, int[] iterationOrder) {
    this.entries = entries;
    this.iterationOrder = iterationOrder;
  }

  /** Logical size of hashtable, power of two. */
  private int tableSize() {
    return entries.length / 2;
  }

  @Override
  public int size() {
    return iterationOrder.length;
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Nullable
  private K keyFromPosOrNull(int pos) {
    return (K) entries[pos * 2];
  }

  @Nullable
  private V valueFromPos(int pos) {
    return (V) entries[pos * 2 + 1];
  }

  private Entry<K, V> entryFromPos(int pos) {
    return new AbstractMap.SimpleEntry<>(keyFromPosOrNull(pos), valueFromPos(pos));
  }

  @Override
  public V get(Object key) {
    if (key == null) {
      return null;
    }

    int mask = tableSize() - 1;
    int hash = Hashtables.smear(key.hashCode());
    int pos = hash & mask;
    for (int i = 0; i < size(); ++i) {
      K entryKey = keyFromPosOrNull(pos);
      if (entryKey == null) {
        return null;
      }
      if (key.equals(entryKey)) {
        return valueFromPos(pos);
      }
      pos = (pos + 1) & mask;
    }
    return null;
  }

  @Override
  public boolean containsKey(Object key) {
    if (key == null) {
      return false;
    }

    int mask = tableSize() - 1;
    int hash = Hashtables.smear(key.hashCode());
    int pos = hash & mask;
    for (int i = 0; i < size(); ++i) {
      K entryKey = keyFromPosOrNull(pos);
      if (entryKey == null) {
        return false;
      }
      if (key.equals(entryKey)) {
        return true;
      }
      pos = (pos + 1) & mask;
    }
    return false;
  }

  @Override
  public boolean containsValue(Object value) {
    return values().contains(value);
  }

  /** Common implementation of all three iterators (over key set, entry set and values). */
  private abstract static class IteratorBase<K, V, R> implements Iterator<R> {

    /** The map. */
    protected final TwoArraysImmutableHashMap<K, V> map;
    /** Current position in iteration order array. */
    private int iterationPos = 0;

    private IteratorBase(TwoArraysImmutableHashMap<K, V> map) {
      this.map = map;
    }

    @Override
    public final boolean hasNext() {
      return iterationPos != map.iterationOrder.length;
    }

    /** Get an element from the hashtable. */
    protected abstract R getFromPos(int pos);

    @Override
    public final R next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      int pos = map.iterationOrder[iterationPos++];
      return getFromPos(pos);
    }
  }

  /** Entry set implementation. */
  private static class EntrySet<K, V> extends AbstractSet<Entry<K, V>> {
    private static final Set<?> EMPTY_ENTRY_SET = new EntrySet<>(EMPTY);

    private final TwoArraysImmutableHashMap<K, V> map;

    public EntrySet(TwoArraysImmutableHashMap<K, V> map) {
      this.map = map;
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) o;
      Object value = map.get(entry.getKey());
      if (entry.getValue() != null) {
        return value != null && value.equals(entry.getValue());
      } else {
        return value == null && map.containsKey(entry.getKey());
      }
    }

    @Override
    public int size() {
      return map.size();
    }

    private static class IteratorImpl<K, V> extends IteratorBase<K, V, Entry<K, V>> {
      private IteratorImpl(TwoArraysImmutableHashMap<K, V> map) {
        super(map);
      }

      @Override
      protected Entry<K, V> getFromPos(int pos) {
        return map.entryFromPos(pos);
      }
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new IteratorImpl<>(map);
    }
  }

  private static class KeySet<K, V> extends AbstractSet<K> {
    private static final Set<?> EMPTY_KEY_SET = new KeySet<>(EMPTY);
    private final TwoArraysImmutableHashMap<K, V> map;

    private KeySet(TwoArraysImmutableHashMap<K, V> map) {
      this.map = map;
    }

    @Override
    public boolean contains(Object o) {
      return map.containsKey(o);
    }

    @Override
    public int size() {
      return map.size();
    }

    private static class IteratorImpl<K, V> extends IteratorBase<K, V, K> {
      private IteratorImpl(TwoArraysImmutableHashMap<K, V> map) {
        super(map);
      }

      @Override
      protected K getFromPos(int pos) {
        return map.keyFromPosOrNull(pos);
      }
    }

    @Override
    public Iterator<K> iterator() {
      return new IteratorImpl<>(map);
    }
  }

  private static class Values<K, V> extends AbstractCollection<V> {
    private static final Collection<?> EMPTY_VALUES = new Values<>(EMPTY);
    private final TwoArraysImmutableHashMap<K, V> map;

    private Values(TwoArraysImmutableHashMap<K, V> map) {
      this.map = map;
    }

    @Override
    public int size() {
      return map.size();
    }

    private static class IteratorImpl<K, V> extends IteratorBase<K, V, V> {
      private IteratorImpl(TwoArraysImmutableHashMap<K, V> map) {
        super(map);
      }

      @Override
      protected V getFromPos(int pos) {
        return map.valueFromPos(pos);
      }
    }

    @Override
    public Iterator<V> iterator() {
      return new IteratorImpl<>(map);
    }
  }

  @Override
  public Set<K> keySet() {
    if (isEmpty()) {
      return (Set<K>) KeySet.EMPTY_KEY_SET;
    }

    return new KeySet<>(this);
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    if (isEmpty()) {
      return (Set<Entry<K, V>>) EntrySet.EMPTY_ENTRY_SET;
    }

    return new EntrySet<>(this);
  }

  @Override
  public Collection<V> values() {
    if (isEmpty()) {
      return (Collection<V>) Values.EMPTY_VALUES;
    }

    return new Values<>(this);
  }

  /** Builder. */
  public static class Builder<K, V> {

    private Object[] entries;
    private int size;

    private Builder() {
      this(0);
    }

    private Builder(int expectedSize) {
      entries = expectedSize != 0 ? new Object[expectedSize * 2] : Hashtables.EMPTY_ARRAY;
      size = 0;
    }

    private void ensureCapacity(int cap) {
      if (entries.length / 2 >= cap) {
        return;
      }
      int newCap = Math.max(4, cap + (cap >> 1));
      entries = Arrays.copyOf(entries, newCap * 2);
    }

    /** Put an element. Key must not be null, and must not be equal to already added key. */
    public Builder<K, V> put(K key, V value) {
      Preconditions.checkNotNull(key);
      this.ensureCapacity(size + 1);
      entries[size * 2] = key;
      entries[size * 2 + 1] = value;
      size += 1;
      return this;
    }

    private K getKey(int i) {
      return (K) entries[i * 2];
    }

    private V getValue(int i) {
      return (V) entries[i * 2 + 1];
    }

    /** Build. */
    public TwoArraysImmutableHashMap<K, V> build() {
      if (size == 0) {
        return (TwoArraysImmutableHashMap<K, V>) EMPTY;
      } else if (size == 1) {
        return new TwoArraysImmutableHashMap<>(
            Hashtables.copyOfOrUse(entries, 2), Hashtables.INT_ARRAY_OF_0);
      } else if (size == 2) {
        int mask = 1;
        int h0 = Hashtables.smear(getKey(0).hashCode());
        int h1 = Hashtables.smear(getKey(1).hashCode());
        if (h0 == h1 && getKey(0).equals(getKey(1))) {
          throw new IllegalStateException("non-unique key");
        }
        if ((h0 & mask) == 0 || (h1 & mask) == 1) {
          return new TwoArraysImmutableHashMap<>(
              Hashtables.copyOfOrUse(entries, 4), Hashtables.INT_ARRAY_OF_0_1);
        } else {
          return new TwoArraysImmutableHashMap<>(
              new Object[] {getKey(1), getValue(1), getKey(0), getValue(0)},
              Hashtables.INT_ARRAY_OF_1_0);
        }
      }

      int[] iterationOrder = new int[size];

      int tableSize = Hashtables.tableSizeForCollectionSize(size);
      Object[] table = new Object[tableSize * 2];
      int mask = tableSize - 1;

      for (int i = 0; i < size; ++i) {
        K key = getKey(i);
        int hash = Hashtables.smear(key.hashCode());
        int pos = hash & mask;
        while (table[pos * 2] != null) {
          if (table[pos * 2].equals(key)) {
            throw new IllegalStateException("non-unique key");
          }
          pos = (pos + 1) & mask;
        }
        table[pos * 2] = key;
        table[pos * 2 + 1] = getValue(i);
        iterationOrder[i] = pos;
      }

      return new TwoArraysImmutableHashMap<>(table, iterationOrder);
    }

    private Builder<K, V> combine(Builder<K, V> that) {
      ensureCapacity(this.size + that.size);
      System.arraycopy(that.entries, 0, this.entries, this.size * 2, that.size * 2);
      this.size += that.size;
      return this;
    }
  }

  /** Builder. */
  public static <K, V> Builder<K, V> builder() {
    return new Builder<>();
  }

  /** Builder with expected map size. */
  public static <K, V> Builder<K, V> builderWithExpectedSize(int size) {
    return new Builder<>(size);
  }

  /** Constructor. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> of() {
    return (TwoArraysImmutableHashMap<K, V>) EMPTY;
  }

  /** Constructor. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> of(K k0, V v0) {
    return TwoArraysImmutableHashMap.<K, V>builder().put(k0, v0).build();
  }

  /** Constructor. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> of(K k0, V v0, K k1, V v1) {
    return TwoArraysImmutableHashMap.<K, V>builder().put(k0, v0).put(k1, v1).build();
  }

  /** Constructor. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> of(K k0, V v0, K k1, V v1, K k2, V v2) {
    return TwoArraysImmutableHashMap.<K, V>builder().put(k0, v0).put(k1, v1).put(k2, v2).build();
  }

  /** Constructor. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> of(
      K k0, V v0, K k1, V v1, K k2, V v2, K k3, V v3) {
    return TwoArraysImmutableHashMap.<K, V>builder()
        .put(k0, v0)
        .put(k1, v1)
        .put(k2, v2)
        .put(k3, v3)
        .build();
  }

  /** Copy another map. */
  public static <K, V> TwoArraysImmutableHashMap<K, V> copyOf(Map<K, V> map) {
    if (map instanceof TwoArraysImmutableHashMap<?, ?>) {
      return (TwoArraysImmutableHashMap<K, V>) map;
    }
    Builder<K, V> builder = builderWithExpectedSize(map.size());
    for (Entry<K, V> entry : map.entrySet()) {
      builder.put(entry.getKey(), entry.getValue());
    }

    return builder.build();
  }

  /** Stream collector. */
  public static <T, K, V> Collector<T, ?, TwoArraysImmutableHashMap<K, V>> toMap(
      Function<? super T, ? extends K> keyFunction,
      Function<? super T, ? extends V> valueFunction) {
    Preconditions.checkNotNull(keyFunction);
    Preconditions.checkNotNull(valueFunction);
    return Collector.of(
        Builder<K, V>::new,
        (builder, input) -> builder.put(keyFunction.apply(input), valueFunction.apply(input)),
        Builder::combine,
        Builder::build);
  }
}
