/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.util.CloseableWrapper.ThrowingConsumer;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Convenience wrapper class to attach closeable functionality to suppliers of resources to be
 * closed. Suppliers will be memoized such that the resources are only closed if we have requested
 * them.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class Main {
 *  public static void main() {
 *    try (CloseableMemoizedSupplier<Resource, Exception> closeableSupplier =
 *        CloseableMemoizedSupplier.of(Resource::new, resource::shutDown)) {
 *      if (condition) {
 *        useResource(closeableSupplier.get())
 *      }
 *    }
 *  }
 * }
 *
 * }</pre>
 *
 * <p>The above will only construct the Resource if condition is true, and close the constructed
 * resource appropriately.
 */
public class CloseableMemoizedSupplier<T, E extends Exception>
    implements AutoCloseable, Supplier<T> {

  private AtomicReference<State> state = new AtomicReference<>(State.UNINITIALIZED);
  private final ThrowingConsumer<T, E> closer;
  private final Supplier<T> supplier;

  private CloseableMemoizedSupplier(Supplier<T> supplier, ThrowingConsumer<T, E> closer) {
    this.supplier = Suppliers.memoize(supplier);
    this.closer = closer;
  }

  /**
   * Wrap a supplier with {@code AutoCloseable} interface and close only if the supplier has been
   * used. Close is idempotent.
   *
   * @param supplier the Supplier of a resource to be closed
   * @param closer the method to close the resource
   */
  public static <T, E extends Exception> CloseableMemoizedSupplier<T, E> of(
      Supplier<T> supplier, ThrowingConsumer<T, E> closer) {
    return new CloseableMemoizedSupplier<>(supplier, closer);
  }

  /** @return the resource from the supplier */
  @Override
  public T get() {
    if (state.compareAndSet(State.UNINITIALIZED, State.INITIALIZED)
        || state.get() == State.INITIALIZED) {
      return supplier.get();
    }
    throw new IllegalStateException("Cannot call get() after close()");
  }

  @Override
  public void close() throws E {
    if (state.compareAndSet(State.UNINITIALIZED, State.CLOSED)) {
      return;
    }
    if (state.compareAndSet(State.INITIALIZED, State.CLOSED)) {
      closer.accept(supplier.get());
    }
  }

  private enum State {
    UNINITIALIZED,
    INITIALIZED,
    CLOSED,
  }
}
