// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import java.lang.reflect.Array;
import java.util.Map;

public class ArrayUtils {

  /**
   * Copies the input array and then applies specified sparse changes.
   *
   * @param clazz target type's Class to cast
   * @param original an array of original elements
   * @param changedElements sparse changes to apply
   * @param <T> target type
   * @return a copy of original arrays while sparse changes are applied
   */
  public static <T> T[] copyWithSparseChanges(
      Class<T[]> clazz, T[] original, Map<Integer, T> changedElements) {
    T[] results = clazz.cast(Array.newInstance(clazz.getComponentType(), original.length));
    int pos = 0;
    for (Map.Entry<Integer, T> entry : changedElements.entrySet()) {
      int i = entry.getKey();
      System.arraycopy(original, pos, results, pos, i - pos);
      results[i] = entry.getValue();
      pos = i + 1;
    }
    if (pos < original.length) {
      System.arraycopy(original, pos, results, pos, original.length - pos);
    }
    return results;
  }

}
