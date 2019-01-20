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

import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for {@link CloseableMemoizedSupplier} and {@link ThrowingCloseableMemoizedSupplier}
 */
public class AbstractCloseableMemoizedSupplier<T, E extends Exception>
    implements AutoCloseable, Supplier<T> {

  private AtomicReference<State> state = new AtomicReference<>(State.UNINITIALIZED);
  private final ThrowingConsumer<T, E> closer;
  private final Supplier<T> supplier;

  protected AbstractCloseableMemoizedSupplier(Supplier<T> supplier, ThrowingConsumer<T, E> closer) {
    this.supplier = Suppliers.memoize(supplier);
    this.closer = closer;
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
