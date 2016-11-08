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

import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * A stream of one element, optimized for use as the return value of functions going into
 * {@link Stream#flatMap(Function)}.
 *
 * Effectively the same as {@code Stream#of}, but with optimized {@link Stream#forEach} and other
 * functions.
 *
 * @see <a href="http://mail.openjdk.java.net/pipermail/core-libs-dev/2015-April/033078.html">
 *        Mailing list message from JDK developer outlining this approach.
 *      </a>
 */
class SingletonStream<T> implements Stream<T> {
  private boolean consumed;
  private final T elem;

  SingletonStream(T t) {
    this.elem = t;
  }

  /**
   * A stream should be operated on only once.
   *
   * This method serves as a precondition check to ensure two stream operations are never called
   * on the same instance.
   *
   * Stream operation functions should either call this immediately, or call {@link #fork()}, which
   * calls this.
   */
  private void consumed() {
    if (consumed) {
      throw new IllegalStateException();
    }
    consumed = true;
  }

  /**
   * Construct a real stream that will handle most of the interface methods.
   */
  private Stream<T> fork() {
    consumed();
    return Stream.of(elem);
  }

  @Override
  public Stream<T> filter(Predicate<? super T> predicate) {
    return fork().filter(predicate);
  }

  @Override
  public <R> Stream<R> map(Function<? super T, ? extends R> mapper) {
    return fork().map(mapper);
  }

  @Override
  public IntStream mapToInt(ToIntFunction<? super T> mapper) {
    return fork().mapToInt(mapper);
  }

  @Override
  public LongStream mapToLong(ToLongFunction<? super T> mapper) {
    return fork().mapToLong(mapper);
  }

  @Override
  public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
    return fork().mapToDouble(mapper);
  }

  @Override
  public <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
    return fork().flatMap(mapper);
  }

  @Override
  public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
    return fork().flatMapToInt(mapper);
  }

  @Override
  public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
    return fork().flatMapToLong(mapper);
  }

  @Override
  public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
    return fork().flatMapToDouble(mapper);
  }

  @Override
  public Stream<T> distinct() {
    return fork().distinct();
  }

  @Override
  public Stream<T> sorted() {
    return fork().sorted();
  }

  @Override
  public Stream<T> sorted(Comparator<? super T> comparator) {
    return fork().sorted(comparator);
  }

  @Override
  public Stream<T> peek(Consumer<? super T> action) {
    return fork().peek(action);
  }

  @Override
  public Stream<T> limit(long maxSize) {
    return fork().limit(maxSize);
  }

  @Override
  public Stream<T> skip(long n) {
    return fork().skip(n);
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    // Do not fork, flatMap implementation uses this operation.
    consumed();
    action.accept(elem);
  }

  @Override
  public void forEachOrdered(Consumer<? super T> action) {
    consumed();
    action.accept(elem);
  }

  @Override
  public Object[] toArray() {
    return fork().toArray();
  }

  @Override
  public <A> A[] toArray(IntFunction<A[]> generator) {
    return fork().toArray(generator);
  }

  @Override
  public T reduce(T identity, BinaryOperator<T> accumulator) {
    consumed();
    return accumulator.apply(identity, elem);
  }

  @Override
  public Optional<T> reduce(BinaryOperator<T> accumulator) {
    consumed();
    return Optional.of(elem);
  }

  @Override
  public <U> U reduce(
      U identity,
      BiFunction<U, ? super T, U> accumulator,
      BinaryOperator<U> combiner) {
    return fork().reduce(identity, accumulator, combiner);
  }

  @Override
  public <R> R collect(
      Supplier<R> supplier,
      BiConsumer<R, ? super T> accumulator,
      BiConsumer<R, R> combiner) {
    return fork().collect(supplier, accumulator, combiner);
  }

  @Override
  public <R, A> R collect(Collector<? super T, A, R> collector) {
    return fork().collect(collector);
  }

  @Override
  public Optional<T> min(Comparator<? super T> comparator) {
    consumed();
    return Optional.of(elem);
  }

  @Override
  public Optional<T> max(Comparator<? super T> comparator) {
    consumed();
    return Optional.of(elem);
  }

  @Override
  public long count() {
    consumed();
    return 1;
  }

  @Override
  public boolean anyMatch(Predicate<? super T> predicate) {
    return fork().anyMatch(predicate);
  }

  @Override
  public boolean allMatch(Predicate<? super T> predicate) {
    return fork().allMatch(predicate);
  }

  @Override
  public boolean noneMatch(Predicate<? super T> predicate) {
    return fork().noneMatch(predicate);
  }

  @Override
  public Optional<T> findFirst() {
    consumed();
    return Optional.of(elem);
  }

  @Override
  public Optional<T> findAny() {
    consumed();
    return Optional.of(elem);
  }

  @Override
  public Iterator<T> iterator() {
    return fork().iterator();
  }

  @Override
  public Spliterator<T> spliterator() {
    return fork().spliterator();
  }

  @Override
  public boolean isParallel() {
    return false;
  }

  @Override
  public Stream<T> sequential() {
    // Do not fork, flatMap implementation uses this operation.
    return this;
  }

  @Override
  public Stream<T> parallel() {
    return fork().parallel();
  }

  @Override
  public Stream<T> unordered() {
    return fork().unordered();
  }

  @Override
  public Stream<T> onClose(Runnable closeHandler) {
    return fork().onClose(closeHandler);
  }

  @Override
  public void close() {
  }
}
