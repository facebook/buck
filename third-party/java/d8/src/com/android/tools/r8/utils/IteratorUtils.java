// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.util.ListIterator;

public class IteratorUtils {
  public static <T> T peekPrevious(ListIterator<T> iterator) {
    T previous = iterator.previous();
    T next = iterator.next();
    assert previous == next;
    return previous;
  }

  public static <T> T peekNext(ListIterator<T> iterator) {
    T next = iterator.next();
    T previous = iterator.previous();
    assert previous == next;
    return next;
  }
}
