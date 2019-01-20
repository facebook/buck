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
import com.google.common.base.Suppliers;

/** Base class for {@code ThrowingCloseableWrapper} and {@code CloseableWrapper} */
public abstract class AbstractCloseableWrapper<T, E extends Exception> implements AutoCloseable {

  private final ThrowingCloseableMemoizedSupplier<T, E> closeable;

  protected AbstractCloseableWrapper(T obj, ThrowingConsumer<T, E> closer) {
    closeable = ThrowingCloseableMemoizedSupplier.of(Suppliers.ofInstance(obj), closer);
    // ensure obj is closed, since {@link CloseableMemoizedSupplier} doesn't call close
    // unless the supplier has been used at least once.
    closeable.get();
  }

  /** @return Original wrapped object */
  public T get() {
    return closeable.get();
  }

  @Override
  public void close() throws E {
    closeable.close();
  }
}
