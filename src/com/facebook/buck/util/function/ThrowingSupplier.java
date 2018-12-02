/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.function;

import com.google.common.base.Throwables;
import java.util.function.Supplier;

/** This version of {@code Supplier<T>} can throw an exception. */
@FunctionalInterface
public interface ThrowingSupplier<T, E extends Exception> {
  T get() throws E;

  /** Returns a Supplier that will wrap any thrown exception in a RuntimeException. */
  default Supplier<T> asSupplier() {
    return () -> {
      try {
        return get();
      } catch (Exception e) {
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
      }
    };
  }

  /** Returns a {@link ThrowingSupplier} from a {@link Supplier} */
  static <T, E extends Exception> ThrowingSupplier<T, E> fromSupplier(Supplier<T> supplier) {
    return () -> supplier.get();
  }
}
