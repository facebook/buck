// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.naming.NamingLens;
import java.util.function.Function;

public interface PresortedComparable<T> extends Presorted, Comparable<T> {

  static <T extends PresortedComparable<T>> boolean isSorted(KeyedDexItem<T>[] items) {
    return isSorted(items, KeyedDexItem::getKey);
  }

  static <S, T extends Comparable<T>> boolean isSorted(S[] items, Function<S, T> getter) {
    T current = null;
    for (S item : items) {
      T next = getter.apply(item);
      if (current != null && current.compareTo(next) >= 0) {
        return false;
      }
      current = next;
    }
    return true;
  }

  // Slow comparison methods that make no use of indices for comparisons. These are used
  // for sorting operations when reading dex files.
  int slowCompareTo(T other);
  int slowCompareTo(T other, NamingLens namingLens);
  // Layered comparison methods that make use of indices for subpart comparisons. These rely
  // on subparts already being sorted and having indices assigned.
  int layeredCompareTo(T other, NamingLens namingLens);

  static <T extends PresortedComparable<T>> int slowCompare(T a, T b) {
    return a.slowCompareTo(b);
  }
}
