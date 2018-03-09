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
import java.util.function.Consumer;

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
 *    try (CloseableMemoizedSupplier<Resource> closeableSupplier =
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
public class CloseableMemoizedSupplier<T>
    extends AbstractCloseableMemoizedSupplier<T, RuntimeException> {

  private CloseableMemoizedSupplier(
      Supplier<T> supplier, ThrowingConsumer<T, RuntimeException> closer) {
    super(supplier, closer);
  }

  /**
   * Wrap a supplier with {@code AutoCloseable} interface and close only if the supplier has been
   * used. Close is idempotent.
   *
   * @param supplier the Supplier of a resource to be closed
   * @param closer the method to close the resource
   */
  public static <T, E extends Exception> CloseableMemoizedSupplier<T> of(
      Supplier<T> supplier, Consumer<T> closer) {
    return new CloseableMemoizedSupplier<>(supplier, toClose -> closer.accept(toClose));
  }
}
