// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

public abstract class KeyedDexItem<T extends PresortedComparable<T>> extends DexItem {

  public abstract T getKey();

  @Override
  public final boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    return (other.getClass() == getClass()) && ((KeyedDexItem<?>) other).getKey().equals(getKey());
  }

  @Override
  public final int hashCode() {
    return getKey().hashCode();
  }
}
