/*
 * Copyright 2015-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.annotation.Nullable;

public class MoreMaps {

  private MoreMaps() {}

  public static <K, V> Map<K, V> putCheckEquals(Map<K, V> map, K key, @Nullable V value) {
    V old = map.put(key, value);
    if (old != null) {
      Preconditions.checkState(old.equals(value));
    }
    return map;
  }

  /**
   * Inserts the given value if nothing was already set for the key. If a value already existed for
   * this key, ensures it is the same as the one being inserted, otherwise throws an
   * IllegalStateException.
   */
  public static <K, V> Map<K, V> putIfAbsentCheckEquals(
      ConcurrentMap<K, V> map, K key, @Nullable V value) {
    V old = map.putIfAbsent(key, value);
    if (old != null) {
      Preconditions.checkState(old.equals(value));
    }
    return map;
  }

  public static <K1, K2, V> ImmutableMap<K2, V> transformKeys(
      Map<K1, V> map, Function<? super K1, K2> transformer) {
    ImmutableMap.Builder<K2, V> transformedMap = ImmutableMap.builder();
    for (Map.Entry<K1, V> ent : map.entrySet()) {
      transformedMap.put(
          Preconditions.checkNotNull(transformer.apply(ent.getKey())), ent.getValue());
    }
    return transformedMap.build();
  }

  public static <K1, K2 extends Comparable<?>, V> ImmutableSortedMap<K2, V> transformKeysAndSort(
      Map<K1, V> map, Function<? super K1, K2> transformer) {
    ImmutableSortedMap.Builder<K2, V> transformedMap = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<K1, V> ent : map.entrySet()) {
      transformedMap.put(
          Preconditions.checkNotNull(transformer.apply(ent.getKey())), ent.getValue());
    }
    return transformedMap.build();
  }

  public static <K, V> ImmutableMap<K, V> merge(Map<K, V> first, Map<K, V> second) {
    Map<K, V> mutableMap = new HashMap<>(first);
    mutableMap.putAll(second);
    return ImmutableMap.copyOf(mutableMap);
  }

  public static <K, V> ImmutableSortedMap<K, V> mergeSorted(Map<K, V> first, Map<K, V> second) {
    Map<K, V> mutableMap = new HashMap<>(first);
    mutableMap.putAll(second);
    return ImmutableSortedMap.copyOf(mutableMap);
  }

  public static <K extends Comparable<?>, V>
      ImmutableSortedMap<K, ImmutableList<V>> convertMultimapToMapOfLists(
          ImmutableMultimap<K, V> multimap) {
    return multimap
        .asMap()
        .entrySet()
        .stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(), e -> e.getKey(), e -> ImmutableList.copyOf(e.getValue())));
  }
}
