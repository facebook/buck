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

import com.facebook.buck.util.function.ThrowingSupplier;
import java.util.function.Supplier;

/**
 * Memoizes a value, supporting passing in a supplier when getting the value, unlike {@link
 * MoreSuppliers.MemoizingSupplier}. See {@link WeakMemoizer} for a weak reference version of this
 * class.
 */
public class Memoizer<T> extends AbstractMemoizer<T, RuntimeException> {

  public T get(Supplier<T> delegate) {
    return get(ThrowingSupplier.fromSupplier(delegate), RuntimeException.class);
  }
}
