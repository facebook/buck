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

import com.google.common.base.Suppliers;

/**
 * Convenience wrapper class to attach closeable functionality to non-closeable class so it can be
 * used with try-with-resources to make sure resources are always released and proper exception
 * suppression is used
 *
 * <p>Example:
 *
 * <pre>{@code
 * class Main {
 *  private static void finalizeMyClass(MyClass obj) throws IOException {
 *    obj.shutdown();
 *  }
 *
 *  public static void main() {
 *    try (CloseableWrapper<MyClass, IOException> myClassWrapper =
 *          CloseableWrapper.of(new MyClass(), Main::finalizeMyClass)) {
 *      myClassWrapper.get().doSomething();
 *    }
 *  }
 * }
 *
 * }</pre>
 */
public class CloseableWrapper<T, E extends Exception> implements AutoCloseable {

  private final CloseableMemoizedSupplier<T, E> closeable;

  private CloseableWrapper(T obj, ThrowingConsumer<T, E> closer) {
    closeable = CloseableMemoizedSupplier.of(Suppliers.ofInstance(obj), closer);
    // ensure obj is closed, since {@link CloseableMemoizedSupplier} doesn't call close
    // unless the supplier has been used at least once.
    closeable.get();
  }

  /**
   * Wrap an object with {@code AutoCloseable} interface and provide a function to replace a {@code
   * close} method The wrapper is idempotent, i.e. it will call closer function exactly once, even
   * if user calls {@code close} multiple times.
   *
   * @param obj Any class that does not implement AutoCloseable interface which is hard to extend
   * @param closer A function to call on close
   */
  public static <T, E extends Exception> CloseableWrapper<T, E> of(
      T obj, ThrowingConsumer<T, E> closer) {
    return new CloseableWrapper<>(obj, closer);
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
