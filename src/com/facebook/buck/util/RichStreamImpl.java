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

import com.facebook.buck.util.exceptions.WrapsException;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
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

/** Implementation that delegates to the standard Stream and wraps the resulting streams. */
final class RichStreamImpl<T> implements RichStream<T> {
  private final Stream<T> delegate;

  RichStreamImpl(Stream<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public RichStream<T> filter(Predicate<? super T> predicate) {
    return new RichStreamImpl<>(delegate.filter(predicate));
  }

  @Override
  public <R> RichStream<R> map(Function<? super T, ? extends R> mapper) {
    return new RichStreamImpl<>(delegate.map(mapper));
  }

  @Override
  public IntStream mapToInt(ToIntFunction<? super T> mapper) {
    return delegate.mapToInt(mapper);
  }

  @Override
  public LongStream mapToLong(ToLongFunction<? super T> mapper) {
    return delegate.mapToLong(mapper);
  }

  @Override
  public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
    return delegate.mapToDouble(mapper);
  }

  @Override
  public <R> RichStream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
    return new RichStreamImpl<>(delegate.flatMap(mapper));
  }

  @Override
  public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
    return delegate.flatMapToInt(mapper);
  }

  @Override
  public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
    return delegate.flatMapToLong(mapper);
  }

  @Override
  public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
    return delegate.flatMapToDouble(mapper);
  }

  @Override
  public RichStream<T> distinct() {
    return new RichStreamImpl<>(delegate.distinct());
  }

  @Override
  public RichStream<T> sorted() {
    return new RichStreamImpl<>(delegate.sorted());
  }

  @Override
  public RichStream<T> sorted(Comparator<? super T> comparator) {
    return new RichStreamImpl<>(delegate.sorted(comparator));
  }

  @Override
  public RichStream<T> peek(Consumer<? super T> action) {
    return new RichStreamImpl<>(delegate.peek(action));
  }

  @Override
  public RichStream<T> limit(long maxSize) {
    return new RichStreamImpl<>(delegate.limit(maxSize));
  }

  @Override
  public RichStream<T> skip(long n) {
    return new RichStreamImpl<>(delegate.skip(n));
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    delegate.forEach(action);
  }

  @Override
  public void forEachOrdered(Consumer<? super T> action) {
    delegate.forEachOrdered(action);
  }

  @Override
  public Object[] toArray() {
    return delegate.toArray();
  }

  @Override
  public <A> A[] toArray(IntFunction<A[]> generator) {
    return delegate.toArray(generator);
  }

  @Override
  public T reduce(T identity, BinaryOperator<T> accumulator) {
    return delegate.reduce(identity, accumulator);
  }

  @Override
  public Optional<T> reduce(BinaryOperator<T> accumulator) {
    return delegate.reduce(accumulator);
  }

  @Override
  public <U> U reduce(
      U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
    return delegate.reduce(identity, accumulator, combiner);
  }

  @Override
  public <R> R collect(
      Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
    return delegate.collect(supplier, accumulator, combiner);
  }

  @Override
  public <R, A> R collect(Collector<? super T, A, R> collector) {
    return delegate.collect(collector);
  }

  @Override
  public Optional<T> min(Comparator<? super T> comparator) {
    return delegate.min(comparator);
  }

  @Override
  public Optional<T> max(Comparator<? super T> comparator) {
    return delegate.max(comparator);
  }

  @Override
  public long count() {
    return delegate.count();
  }

  @Override
  public boolean anyMatch(Predicate<? super T> predicate) {
    return delegate.anyMatch(predicate);
  }

  @Override
  public boolean allMatch(Predicate<? super T> predicate) {
    return delegate.allMatch(predicate);
  }

  @Override
  public boolean noneMatch(Predicate<? super T> predicate) {
    return delegate.noneMatch(predicate);
  }

  @Override
  public Optional<T> findFirst() {
    return delegate.findFirst();
  }

  @Override
  public Optional<T> findAny() {
    return delegate.findAny();
  }

  @Override
  public Iterator<T> iterator() {
    return delegate.iterator();
  }

  @Override
  public Spliterator<T> spliterator() {
    return delegate.spliterator();
  }

  @Override
  public boolean isParallel() {
    return delegate.isParallel();
  }

  @Override
  public RichStream<T> sequential() {
    return new RichStreamImpl<>(delegate.sequential());
  }

  @Override
  public RichStream<T> parallel() {
    return new RichStreamImpl<>(delegate.parallel());
  }

  @Override
  public RichStream<T> unordered() {
    return new RichStreamImpl<>(delegate.unordered());
  }

  @Override
  public RichStream<T> onClose(Runnable closeHandler) {
    return new RichStreamImpl<>(delegate.onClose(closeHandler));
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public <E extends Exception> void forEachThrowing(ThrowingConsumer<? super T, E> action)
      throws E {
    try {
      forEach(wrapped(action));
    } catch (TunneledException e) {
      throw this.<E>unwrap(e);
    }
  }

  @Override
  public <E extends Exception> void forEachOrderedThrowing(ThrowingConsumer<? super T, E> action)
      throws E {
    try {
      forEachOrdered(wrapped(action));
    } catch (TunneledException e) {
      throw this.<E>unwrap(e);
    }
  }

  /**
   * Returns a consumer which wraps the exception thrown by the {@link ThrowingConsumer} into an
   * exception tunnel associated with this object.
   */
  private <E extends Exception> Consumer<? super T> wrapped(ThrowingConsumer<? super T, E> action) {
    return t -> {
      try {
        action.accept(t);
      } catch (Exception exception) {
        // exception: E | RuntimeException | Error
        Throwables.throwIfUnchecked(exception);
        // exception: E
        throw new TunneledException(this, exception);
      }
    };
  }

  /**
   * Unwrap the exception inside a {@link TunneledException}. This exception is checked to originate
   * from the current stream instance, to guard against mistakes that leak {@code
   * TunneledException}s.
   */
  private <E extends Exception> E unwrap(TunneledException e) {
    // e.getCause(): E | AnythingElse
    Preconditions.checkState(
        e.owner == this, "Caught TunneledException should be the one thrown by the same stream.");
    // e.getCause(): E
    @SuppressWarnings("unchecked")
    E cause = (E) e.getCause();
    return cause;
  }

  /**
   * An exception used to tunnel checked exceptions through a non-throwing interface.
   *
   * <p>The exception is keyed on the current object. Since a stream object is responsible for a
   * single operation, this value should be sufficient to differentiate an instance thrown from the
   * current stream object, and an instance thrown by another.
   */
  private static final class TunneledException extends RuntimeException implements WrapsException {
    final Object owner;

    TunneledException(Object owner, Throwable cause) {
      super(cause);
      this.owner = owner;
    }
  }
}
