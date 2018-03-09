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
import java.util.function.Consumer;

/**
 * Convenience wrapper class to attach closeable functionality to non-closeable class so it can be
 * used with try-with-resources to make sure resources are always released and proper exception
 * suppression is used. The closer may not throw an exception.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class Main {
 *  private static void finalizeMyClass(MyClass obj) {
 *    obj.shutdown();
 *  }
 *
 *  public static void main() {
 *    try (CloseableWrapper<MyClass> myClassWrapper =
 *          CloseableWrapper.of(new MyClass(), Main::finalizeMyClass)) {
 *      myClassWrapper.get().doSomething();
 *    }
 *  }
 * }
 *
 * }</pre>
 */
public class CloseableWrapper<T> extends AbstractCloseableWrapper<T, RuntimeException> {
  private CloseableWrapper(T obj, ThrowingConsumer<T, RuntimeException> closer) {
    super(obj, closer);
  }

  /**
   * Wrap an object with {@code AutoCloseable} interface and provide a function to replace a {@code
   * close} method. The wrapper is idempotent, i.e. it will call closer function exactly once, even
   * if user calls {@code close} multiple times.
   *
   * @param obj Any class that does not implement AutoCloseable interface which is hard to extend
   * @param closer A function to call on close
   */
  public static <T> CloseableWrapper<T> of(T obj, Consumer<T> closer) {
    return new CloseableWrapper<>(obj, t -> closer.accept(t));
  }
}
