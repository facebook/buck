// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class OrderedMergingIterator<T extends KeyedDexItem<S>, S extends PresortedComparable<S>>
    implements Iterator<T> {

  private final T[] one;
  private final T[] other;
  private int oneIndex = 0;
  private int otherIndex = 0;

  public OrderedMergingIterator(T[] one, T[] other) {
    this.one = one;
    this.other = other;
  }

  private static <T> T getNextChecked(T[] array, int position) {
    if (position >= array.length) {
      throw new NoSuchElementException();
    }
    return array[position];
  }

  @Override
  public boolean hasNext() {
    return oneIndex < one.length || otherIndex < other.length;
  }

  @Override
  public T next() {
    if (oneIndex >= one.length) {
      return getNextChecked(other, otherIndex++);
    }
    if (otherIndex >= other.length) {
      return getNextChecked(one, oneIndex++);
    }
    int comparison = one[oneIndex].getKey().compareTo(other[otherIndex].getKey());
    if (comparison < 0) {
      return one[oneIndex++];
    }
    if (comparison == 0) {
      throw new InternalCompilerError("Source arrays are not disjoint.");
    }
    return other[otherIndex++];
  }
}
