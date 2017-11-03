// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import java.util.Arrays;

public class DexEncodedArray extends DexItem {

  public final DexValue[] values;

  public DexEncodedArray(DexValue[] values) {
    this.values = values;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    collectAll(indexedItems, values);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(this);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    return (o instanceof DexEncodedArray) && (Arrays.equals(((DexEncodedArray) o).values, values));
  }

  @Override
  public String toString() {
    return "EncodedArray " + Arrays.toString(values);
  }
}
