// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import java.util.Arrays;

public class DexEncodedAnnotation extends DexItem {

  private static final int UNSORTED = 0;

  public final DexType type;
  public final DexAnnotationElement[] elements;

  private int sorted = UNSORTED;

  public DexEncodedAnnotation(DexType type, DexAnnotationElement[] elements) {
    this.type = type;
    this.elements = elements;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    type.collectIndexedItems(indexedItems);
    collectAll(indexedItems, elements);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    // Should never be called.
    assert false;
  }

  @Override
  public String toString() {
    return "Encoded annotation " + type + " " + Arrays.toString(elements);
  }

  @Override
  public int hashCode() {
    return type.hashCode() * 7 + Arrays.hashCode(elements);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DexEncodedAnnotation) {
      DexEncodedAnnotation that = (DexEncodedAnnotation) other;
      return that.type.equals(type) && Arrays.equals(that.elements, elements);
    }
    return false;
  }

  public void sort() {
    if (sorted != UNSORTED) {
      assert sorted == sortedHashCode();
      return;
    }
    Arrays.sort(elements, (a, b) -> a.name.compareTo(b.name));
    for (DexAnnotationElement element : elements) {
      element.value.sort();
    }
    sorted = sortedHashCode();
  }

  private int sortedHashCode() {
    int hashCode = hashCode();
    return hashCode == UNSORTED ? 1 : hashCode;
  }
}
