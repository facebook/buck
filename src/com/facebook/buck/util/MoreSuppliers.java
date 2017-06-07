/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.base.Supplier;
import java.lang.ref.WeakReference;
import javax.annotation.Nullable;

public final class MoreSuppliers {
  private MoreSuppliers() {}

  public static <T> Supplier<T> weakMemoize(Supplier<T> delegate) {
    return (delegate instanceof WeakMemoizingSupplier)
        ? delegate
        : new WeakMemoizingSupplier<>(delegate);
  }

  private static class WeakMemoizingSupplier<T> implements Supplier<T> {
    private final Supplier<T> delegate;
    private WeakReference<T> valueRef;

    public WeakMemoizingSupplier(Supplier<T> delegate) {
      this.delegate = delegate;
      this.valueRef = new WeakReference<>(null);
    }

    @Override
    public T get() {
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
}
