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

import java.lang.ref.WeakReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Memoizes a value with a weak reference, supporting passing in a supplier when getting the value,
 * unlike {@link MoreSuppliers.WeakMemoizingSupplier}
 */
public class WeakMemoizer<T> {
  private WeakReference<T> valueRef = new WeakReference<>(null);

  /**
   * Get the value and memoize the result. Constructs the value if it hasn't been memoized before,
   * or if the previously memoized value has been collected.
   *
   * @param delegate delegate for constructing the value
   * @return the value
   */
  public T get(Supplier<T> delegate) {
    @Nullable T value = valueRef.get();
    if (value == null) {
      synchronized (this) {
        // Check again in case someone else has populated the cache.
        value = valueRef.get();
        if (value == null) {
          value = delegate.get();
          valueRef = new WeakReference<>(value);
        }
      }
    }
    return value;
  }
}
