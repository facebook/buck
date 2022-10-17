/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.function;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Functional interface wrappers that allow you to throw checked exceptions.
 *
 * <p>Checked exceptions are only actually checked at compile time. At run time they are thrown and
 * caught like any other exception. {@link #sneakyThrow} converts a checked exception into an
 * unchecked one. The net effect is that you can get functional interface implementations that throw
 * checked exceptions, without the caller needing to know about it.
 *
 * <p>Obviously these exceptions are still thrown, not swallowed, so you need to be ready to handle
 * them. Ye have been warned.
 *
 * <p>Example: <code>
 *   byte[] loadFile(String filename) throws IOException { ... }
 *
 *   // Before:
 *   Stream.from(filenames).map(f -> loadFile(f));
 *   // Doesn't build; uncaught IOException
 *
 *   // Workaround:
 *   Stream.from(filenames).map(f -> {
 *         try {
 *           return loadFile(f);
 *         } catch (IOException e) {
 *           throw new RuntimeException(e);
 *         }
 *       });
 *   // This is painfully long, ugly, hard to read, and the IOException is wrapped.
 *
 *   // After:
 *   Stream.from(filenames).map(uncheckedFunction(f -> loadFile(f)));
 *   // Builds just fine and IOException propogates properly.
 * </code>
 */
public abstract class UncheckedFunctions {
  /** No instances, only statics. */
  private UncheckedFunctions() {}

  @SuppressWarnings("unchecked")
  private static <T extends Exception, R> R sneakyThrow(Exception t) throws T {
    throw (T) t;
  }

  /** See {@link #uncheckedFunction}. */
  public interface ThrowingFunction<T, R> {
    R apply(T t) throws Exception;
  }

  /** Wraps a Function that can throw a checked exception in a Function that doesn't. */
  public static <T, R> Function<T, R> uncheckedFunction(ThrowingFunction<T, R> f) {
    return t -> {
      try {
        return f.apply(t);
      } catch (Exception ex) {
        return sneakyThrow(ex);
      }
    };
  }

  /** See {@link #uncheckedSupplier}. */
  public interface ThrowingSupplier<R> {
    R get() throws Exception;
  }

  /** Wraps a Supplier that can throw a checked exception in a Supplier that doesn't. */
  public static <R> Supplier<R> uncheckedSupplier(ThrowingSupplier<R> f) {
    return () -> {
      try {
        return f.get();
      } catch (Exception ex) {
        return sneakyThrow(ex);
      }
    };
  }

  /** See {@link #uncheckedConsumer}. */
  public interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }

  /** Wraps a Consumer that can throw a checked exception in a Consumer that doesn't. */
  public static <T> Consumer<T> uncheckedConsumer(ThrowingConsumer<T> f) {
    return t -> {
      try {
        f.accept(t);
      } catch (Exception ex) {
        sneakyThrow(ex);
      }
    };
  }

  /** See {@link #uncheckedBiConsumer}. */
  public interface ThrowingBiConsumer<T1, T2> {
    void accept(T1 t1, T2 t2) throws Exception;
  }

  /** Wraps a BiConsumer that can throw a checked exception in a BiConsumer that doesn't. */
  public static <T1, T2> BiConsumer<T1, T2> uncheckedBiConsumer(ThrowingBiConsumer<T1, T2> f) {
    return (t1, t2) -> {
      try {
        f.accept(t1, t2);
      } catch (Exception ex) {
        sneakyThrow(ex);
      }
    };
  }
}
