// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import java.util.Arrays;

public class DexAnnotationSetRefList extends DexItem {

  private static final DexAnnotationSetRefList theEmptyTypeList = new DexAnnotationSetRefList();

  public final DexAnnotationSet[] values;

  public static DexAnnotationSetRefList empty() {
    return theEmptyTypeList;
  }

  private DexAnnotationSetRefList() {
    this.values = new DexAnnotationSet[0];
  }

  public DexAnnotationSetRefList(DexAnnotationSet[] values) {
    assert values != null && values.length > 0;
    this.values = values;
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DexAnnotationSetRefList) {
      return Arrays.equals(values, ((DexAnnotationSetRefList) other).values);
    }
    return false;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    collectAll(indexedItems, values);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    // Collect values first so that the annotation sets have sorted themselves before adding this.
    collectAll(mixedItems, values);
    mixedItems.add(this);
  }

  public boolean isEmpty() {
    return values.length == 0;
  }
}
