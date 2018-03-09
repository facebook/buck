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

import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Java 8 streams with "rich" API (convenience methods).
 *
 * <p>The default Java 8 Stream implementation is deliberately spartan in API methods in order to
 * avoid dependencies on Collections, and to focus on parallelism. This wrapper adds common
 * operations to the Stream API, mostly inspired by Guava's {@code FluentIterable}.
 *
 * @param <T> the type of the stream elements.
 */
public interface RichStream<T> extends Stream<T> {
  // Static methods

  static <T> RichStream<T> empty() {
    return new RichStreamImpl<>(Stream.empty());
  }

  static <T> RichStream<T> of(T value) {
    return new RichStreamImpl<>(Stream.of(value));
  }

  @SafeVarargs
  static <T> RichStream<T> of(T... values) {
    return new RichStreamImpl<>(Stream.of(values));
  }

  static <T> RichStream<T> from(T[] array) {
    return new RichStreamImpl<>(Arrays.stream(array));
  }

  static <T> RichStream<T> from(Iterable<T> iterable) {
    return new RichStreamImpl<>(StreamSupport.stream(iterable.spliterator(), false));
  }

  static <T> RichStream<T> from(Iterator<T> iterator) {
    return new RichStreamImpl<>(
        StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false));
  }

  static <T> RichStream<T> from(Optional<? extends T> optional) {
    return optional.isPresent() ? of(optional.get()) : empty();
  }

  static <T> RichStream<T> fromSupplierOfIterable(Supplier<? extends Iterable<T>> supplier) {
    return new RichStreamImpl<>(StreamSupport.stream(() -> supplier.get().spliterator(), 0, false));
  }

  static <T> RichStream<T> from(Stream<T> stream) {
    if (stream instanceof RichStream) {
      return (RichStream<T>) stream;
    } else {
      return new RichStreamImpl<>(stream);
    }
  }

  // Convenience functions that can be implemented with existing primitives.

  /**
   * @param other Stream to concatenate into the current one.
   * @return Stream containing the elements of the current stream followed by that of the given
   *     stream.
   */
  default RichStream<T> concat(Stream<T> other) {
    return new RichStreamImpl<>(Stream.concat(this, other));
  }

  /**
   * Filter stream elements, returning only those that is an instance of the given class.
   *
   * @param cls Class instance of the class to cast to.
   * @param <U> Class to cast to.
   * @return Filtered stream of instances of {@code cls}.
   */
  @SuppressWarnings("unchecked")
  default <U> RichStream<U> filter(Class<U> cls) {
    return (RichStream<U>) filter(cls::isInstance);
  }

  default ImmutableList<T> toImmutableList() {
    return collect(ImmutableList.toImmutableList());
  }

  default ImmutableSet<T> toImmutableSet() {
    return collect(ImmutableSet.toImmutableSet());
  }

  default ImmutableSortedSet<T> toImmutableSortedSet(Comparator<? super T> ordering) {
    return collect(ImmutableSortedSet.toImmutableSortedSet(ordering));
  }

  default Iterable<T> toOnceIterable() {
    return this::iterator;
  }

  /**
   * A variant of {@link Stream#forEach} that can safely throw a checked exception.
   *
   * @param <E> Exception that may be thrown by the action.
   * @throws E Exception thrown by the action.
   */
  <E extends Exception> void forEachThrowing(ThrowingConsumer<? super T, E> action) throws E;

  /**
   * A variant of {@link Stream#forEachOrdered} that can safely throw a checked exception.
   *
   * @param <E> Exception that may be thrown by the action.
   * @throws E Exception thrown by the action.
   */
  <E extends Exception> void forEachOrderedThrowing(ThrowingConsumer<? super T, E> action) throws E;

  // More specific return types for Stream methods that return Streams.

  @Override
  RichStream<T> filter(Predicate<? super T> predicate);

  @Override
  <R> RichStream<R> map(Function<? super T, ? extends R> mapper);

  @Override
  <R> RichStream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper);

  @Override
  RichStream<T> distinct();

  @Override
  RichStream<T> sorted();

  @Override
  RichStream<T> sorted(Comparator<? super T> comparator);

  @Override
  RichStream<T> peek(Consumer<? super T> action);

  @Override
  RichStream<T> limit(long maxSize);

  @Override
  RichStream<T> skip(long n);

  @Override
  RichStream<T> sequential();

  @Override
  RichStream<T> parallel();

  @Override
  RichStream<T> unordered();

  @Override
  RichStream<T> onClose(Runnable closeHandler);
}
