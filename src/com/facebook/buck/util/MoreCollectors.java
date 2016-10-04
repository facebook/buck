/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.util.function.Function;
import java.util.stream.Collector;

public final class MoreCollectors {
  private MoreCollectors() {}

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableList}.
   *
   * This {@code Collector} behaves similar to
   * {@code Collectors.collectingAndThen(Collectors.toList(), ImmutableList::copyOf)} but without
   * building the intermediate list.
   *
   * @param <T> the type of the input elements
   * @return a {@code Collector} that builds an {@code ImmutableList}.
   */
  public static <T> Collector<T, ?, ImmutableList<T>> toImmutableList() {
    return Collector.<T, ImmutableList.Builder<T>, ImmutableList<T>>of(
        ImmutableList::builder,
        ImmutableList.Builder::add,
        (left, right) -> left.addAll(right.build()),
        ImmutableList.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableSet}.
   *
   * This {@code Collector} behaves similar to
   * {@code Collectors.collectingAndThen(Collectors.toList(), ImmutableSet::copyOf)} but without
   * building the intermediate list.
   *
   * @param <T> the type of the input elements
   * @return a {@code Collector} that builds an {@code ImmutableSet}.
   */
  public static <T> Collector<T, ?, ImmutableSet<T>> toImmutableSet() {
    return Collector.<T, ImmutableSet.Builder<T>, ImmutableSet<T>>of(
        ImmutableSet::builder,
        ImmutableSet.Builder::add,
        (left, right) -> left.addAll(right.build()),
        ImmutableSet.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableSortedSet}.
   *
   * This {@code Collector} behaves similar to
   * {@code Collectors.collectingAndThen(Collectors.toList(), ImmutableSortedSet::copyOf)} but
   * without building the intermediate list.
   *
   * @param <T> the type of the input elements
   * @return a {@code Collector} that builds an {@code ImmutableSortedSet}.
   */
  public static <T> Collector<T, ?, ImmutableSortedSet<T>> toImmutableSortedSet(
      Ordering<T> ordering) {
    return Collector.<T, ImmutableSortedSet.Builder<T>, ImmutableSortedSet<T>>of(
        () -> ImmutableSortedSet.orderedBy(ordering),
        ImmutableSet.Builder::add,
        (left, right) -> left.addAll(right.build()),
        ImmutableSortedSet.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableMap}, whose keys and values are the
   * result of applying mapping functions to the input elements.
   *
   * This {@code Collector} behaves similar to
   * {@code Collectors.collectingAndThen(Collectors.toMap(), ImmutableMap::copyOf)} but
   * preserves iteration order and does not build an intermediate map.
   *
   * @param <T> the type of the input elements
   * @param <K> the output type of the key mapping function
   * @param <U> the output type of the value mapping function
   *
   * @param keyMapper a mapping function to produce keys
   * @param valueMapper a mapping function to produce values
   *
   * @return a {@code Collector} that builds an {@code ImmutableMap}.
   */
  public static <T, K, U> Collector<T, ?, ImmutableMap<K, U>> toImmutableMap(
      Function<? super T, ? extends K> keyMapper,
      Function<? super T, ? extends U> valueMapper) {
    return Collector.<T, ImmutableMap.Builder<K, U>, ImmutableMap<K, U>>of(
        ImmutableMap::builder,
        (builder, elem) -> builder.put(keyMapper.apply(elem), valueMapper.apply(elem)),
        (left, right) -> left.putAll(right.build()),
        ImmutableMap.Builder::build);
  }
}
