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

import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Memoizes a value, supporting passing in a supplier when getting the value, unlike {@link
 * MoreSuppliers.MemoizingSupplier}. See {@link WeakMemoizer} for a weak reference version of this
 * class.
 */
public class Memoizer<T> {
  private volatile Optional<T> value = Optional.empty();

  /**
   * Get the value and memoize the result. Constructs the value if it hasn't been memoized before,
   * or if the previously memoized value has been collected.
   *
   * @param delegate delegate for constructing the value
   * @return the value
   */
  public T get(Supplier<T> delegate) {
    if (!value.isPresent()) {
      synchronized (this) {
        if (!value.isPresent()) {
          T t = Preconditions.checkNotNull(delegate.get());
          value = Optional.of(t);
          return t;
        }
      }
    }
    return Preconditions.checkNotNull(value.get());
  }
}
