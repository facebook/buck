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

/**
 * Convenience wrapper class to attach closeable functionality to non-closeable class so it can be
 * used with try-with-resources to make sure resources are always released and proper exception
 * suppression is used
 *
 * <p>Example:
 *
 * <p>class Main {
 *
 * <p>private static void finalizeMyClass(MyClass obj) throws IOException {
 *
 * <p>obj.shutdown();
 *
 * <p>}
 *
 * <p>public static void main() {
 *
 * <p>try (CloseableWrapper<MyClass, IOException> myClassWrapper =
 *
 * <p>CloseableWrapper.of(new MyClass(), Main::finalizeMyClass)) {
 *
 * <p>myClassWrapper.get().doSomething();
 *
 * <p>}
 *
 * <p>}
 */
public class CloseableWrapper<T, E extends Exception> implements AutoCloseable {

  private final T obj;
  private final ThrowingConsumer<T, E> closer;
  private boolean closed = false;

  private CloseableWrapper(T obj, ThrowingConsumer<T, E> closer) {
    this.obj = obj;
    this.closer = closer;
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
    return obj;
  }

  @Override
  public void close() throws E {
    if (closed) {
      return;
    }
    closed = true;
    closer.accept(obj);
  }

  /**
   * The version of {@code Consumer<T>} that can throw an exception. It only exists because {@code
   * AutoCloseable} interface defines its {@code close} function to throw {@code Exception}
   */
  @FunctionalInterface
  public interface ThrowingConsumer<T, E extends Exception> {
    void accept(T t) throws E;
  }
}
