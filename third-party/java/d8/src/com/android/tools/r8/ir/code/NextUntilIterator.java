// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import java.util.Iterator;
import java.util.function.Predicate;

public interface NextUntilIterator<T> extends Iterator<T> {

  /**
   * Continue to call {@link #next} while {@code predicate} tests {@code false}.
   *
   * @returns the item that matched the predicate or {@code null} if all items fail
   * the predicate test
   */
  default T nextUntil(Predicate<T> predicate) {
    while (hasNext()) {
      T item = next();
      if (predicate.test(item)) {
        return item;
      }
    }
    return null;
  }
}
