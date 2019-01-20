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

import com.facebook.buck.util.function.ThrowingSupplier;
import java.util.Objects;
import java.util.function.Supplier;

public final class MoreSuppliers {
  private MoreSuppliers() {}

  /**
   * Returns a supplier which caches the instance retrieved during the first call to {@code get()}
   * and returns that value on subsequent calls to {@code get()}.
   *
   * <p>Unlike Guava's {@link
   * com.google.common.base.Suppliers#memoize(com.google.common.base.Supplier)}, this version
   * removes the reference to the underlying Supplier once the value is computed. This frees up
   * memory used in lambda captures, at the cost of causing the supplier to be not Serializable.
   */
  public static <T> Supplier<T> memoize(Supplier<T> delegate) {
    return (delegate instanceof MemoizingSupplier) ? delegate : new MemoizingSupplier<T>(delegate);
  }

  /**
   * Returns a {@link ThrowingSupplier} that caches the result or the exception during the first
   * call to {@code get()}, and returns or throws on subsequent calls to {@code get()}
   */
  public static <T, E extends Exception> ThrowingSupplier<T, E> memoize(
      ThrowingSupplier<T, E> delegate, Class<E> exceptionClass) {
    return (delegate instanceof MemoizingThrowingSupplier)
        ? delegate
        : new MemoizingThrowingSupplier<>(delegate, exceptionClass);
  }

  private static class MemoizingThrowingSupplier<T, E extends Exception>
      extends ThrowingMemoizer<T, E> implements ThrowingSupplier<T, E> {

    private final ThrowingSupplier<T, E> delegate;
    private final Class<E> exceptionClass;

    public MemoizingThrowingSupplier(ThrowingSupplier<T, E> delegate, Class<E> exceptionClass) {
      this.delegate = delegate;
      this.exceptionClass = exceptionClass;
    }

    @Override
    public T get() throws E {
      return get(delegate, exceptionClass);
    }
  }

  private static class MemoizingSupplier<T> extends Memoizer<T> implements Supplier<T> {
    private final Supplier<T> delegate;

    public MemoizingSupplier(Supplier<T> delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public T get() {
      return get(delegate);
    }
  }

  public static <T> Supplier<T> weakMemoize(Supplier<T> delegate) {
    return (delegate instanceof WeakMemoizingSupplier)
        ? delegate
        : new WeakMemoizingSupplier<>(delegate);
  }

  private static class WeakMemoizingSupplier<T> extends WeakMemoizer<T> implements Supplier<T> {
    private final Supplier<T> delegate;

    public WeakMemoizingSupplier(Supplier<T> delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public T get() {
      return get(delegate);
    }
  }
}
