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
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Collector;

public final class MoreCollectors {
  private MoreCollectors() {}

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableList}.
   *
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toList(), ImmutableList::copyOf)} but without building
   * the intermediate list.
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
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toList(), ImmutableSet::copyOf)} but without building
   * the intermediate list.
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
   * <p>This {@code Collector} behaves similar to: {@code Collectors.collectingAndThen(
   * Collectors.toList(), list -> ImmutableSortedSet.copyOf(comparator, list)) } but without
   * building the intermediate list.
   *
   * @param <T> the type of the input elements
   * @return a {@code Collector} that builds an {@code ImmutableSortedSet}.
   */
  public static <T extends Comparable<T>>
      Collector<T, ?, ImmutableSortedSet<T>> toImmutableSortedSet() {
    return Collector.of(
        () -> new ImmutableSortedSet.Builder<T>(Ordering.natural()),
        ImmutableSortedSet.Builder::add,
        (left, right) -> left.addAll(right.build()),
        ImmutableSortedSet.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableSortedSet}.
   *
   * <p>This {@code Collector} behaves similar to: {@code Collectors.collectingAndThen(
   * Collectors.toList(), list -> ImmutableSortedSet.copyOf(comparator, list)) } but without
   * building the intermediate list.
   *
   * @param <T> the type of the input elements
   * @param ordering comparator used to order the elements.
   * @return a {@code Collector} that builds an {@code ImmutableSortedSet}.
   */
  public static <T> Collector<T, ?, ImmutableSortedSet<T>> toImmutableSortedSet(
      Comparator<? super T> ordering) {
    return Collector.of(
        () -> new ImmutableSortedSet.Builder<T>(ordering),
        ImmutableSortedSet.Builder::add,
        (left, right) -> left.addAll(right.build()),
        ImmutableSortedSet.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableMap}, whose keys and values are the
   * result of applying mapping functions to the input elements.
   *
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toMap(), ImmutableMap::copyOf)} but preserves iteration
   * order and does not build an intermediate map.
   *
   * @param <T> the type of the input elements
   * @param <K> the output type of the key mapping function
   * @param <U> the output type of the value mapping function
   * @param keyMapper a mapping function to produce keys
   * @param valueMapper a mapping function to produce values
   * @return a {@code Collector} that builds an {@code ImmutableMap}.
   */
  public static <T, K, U> Collector<T, ?, ImmutableMap<K, U>> toImmutableMap(
      Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper) {
    return Collector.<T, ImmutableMap.Builder<K, U>, ImmutableMap<K, U>>of(
        ImmutableMap::builder,
        (builder, elem) -> builder.put(keyMapper.apply(elem), valueMapper.apply(elem)),
        (left, right) -> left.putAll(right.build()),
        ImmutableMap.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableSortedMap}, whose keys and values
   * are the result of applying mapping functions to the input elements.
   *
   * <p>The built map uses the natural ordering for elements.
   *
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toMap(), ImmutableSortedMap::copyOf)} but does not
   * build an intermediate map.
   *
   * @param <T> the type of the input elements
   * @param <K> the output type of the key mapping function
   * @param <U> the output type of the value mapping function
   * @param keyMapper a mapping function to produce keys
   * @param valueMapper a mapping function to produce values
   * @return a {@code Collector} that builds an {@code ImmutableMap}.
   */
  public static <T, K extends Comparable<?>, U>
      Collector<T, ?, ImmutableSortedMap<K, U>> toImmutableSortedMap(
          Function<? super T, ? extends K> keyMapper,
          Function<? super T, ? extends U> valueMapper) {
    return Collector.<T, ImmutableSortedMap.Builder<K, U>, ImmutableSortedMap<K, U>>of(
        ImmutableSortedMap::naturalOrder,
        (builder, elem) -> builder.put(keyMapper.apply(elem), valueMapper.apply(elem)),
        (left, right) -> left.putAll(right.build()),
        ImmutableSortedMap.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableMultimap}, whose keys and values are
   * the result of applying mapping functions to the input elements.
   *
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toMap(), ImmutableMultimap::copyOf)} but preserves
   * iteration order and does not build an intermediate map.
   *
   * @param <T> the type of the input elements
   * @param <K> the output type of the key mapping function
   * @param <U> the output type of the value mapping function
   * @param keyMapper a mapping function to produce keys
   * @param valueMapper a mapping function to produce values
   * @return a {@code Collector} that builds an {@code ImmutableMultimap}.
   */
  public static <T, K, U> Collector<T, ?, ImmutableMultimap<K, U>> toImmutableMultimap(
      Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper) {
    return Collector.<T, ImmutableMultimap.Builder<K, U>, ImmutableMultimap<K, U>>of(
        ImmutableListMultimap::builder,
        (builder, elem) -> builder.put(keyMapper.apply(elem), valueMapper.apply(elem)),
        (left, right) -> left.putAll(right.build()),
        ImmutableMultimap.Builder::build);
  }

  /**
   * Returns a {@code Collector} that builds an {@code ImmutableListMultimap}, whose keys and values
   * are the result of applying mapping functions to the input elements.
   *
   * <p>This {@code Collector} behaves similar to {@code
   * Collectors.collectingAndThen(Collectors.toMap(), ImmutableListMultimap::copyOf)} but preserves
   * iteration order and does not build an intermediate map.
   *
   * @param <T> the type of the input elements
   * @param <K> the output type of the key mapping function
   * @param <U> the output type of the value mapping function
   * @param keyMapper a mapping function to produce keys
   * @param valueMapper a mapping function to produce values
   * @return a {@code Collector} that builds an {@code ImmutableListMultimap}.
   */
  public static <T, K, U> Collector<T, ?, ImmutableListMultimap<K, U>> toImmutableListMultimap(
      Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper) {
    return Collector.<T, ImmutableListMultimap.Builder<K, U>, ImmutableListMultimap<K, U>>of(
        ImmutableListMultimap::builder,
        (builder, elem) -> builder.put(keyMapper.apply(elem), valueMapper.apply(elem)),
        (left, right) -> left.putAll(right.build()),
        ImmutableListMultimap.Builder::build);
  }
}
