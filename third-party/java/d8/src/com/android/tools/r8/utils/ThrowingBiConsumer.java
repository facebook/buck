// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import java.util.function.BiConsumer;

/**
 * Similar to a {@link BiConsumer} but throws a single {@link Throwable}.
 *
 * @param <T> the type of the first argument
 * @param <U> the type of the second argument
 * @param <E> the type of the {@link Throwable}
 */
@FunctionalInterface
public interface ThrowingBiConsumer<T, U, E extends Throwable> {
  void accept(T t, U u) throws E;
}
