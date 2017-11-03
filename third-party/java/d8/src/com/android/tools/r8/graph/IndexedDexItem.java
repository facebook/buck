// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;

/**
 * Subset of dex items that are referenced by some table index.
 */
public abstract class IndexedDexItem extends CachedHashValueDexItem implements Presorted {

  private static final int SORTED_INDEX_UNKNOWN = -1;
  private int sortedIndex = SORTED_INDEX_UNKNOWN; // assigned globally after reading.

  @Override
  public abstract void collectIndexedItems(IndexedItemCollection indexedItems);

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    // Should never be visited.
    assert false;
  }

  public abstract int getOffset(ObjectToOffsetMapping mapping);

  // Partial implementation of PresortedComparable.

  @Override
  final public void setSortedIndex(int sortedIndex) {
    assert sortedIndex > SORTED_INDEX_UNKNOWN;
    assert this.sortedIndex == SORTED_INDEX_UNKNOWN;
    this.sortedIndex = sortedIndex;
  }

  @Override
  final public int getSortedIndex() {
    return sortedIndex;
  }

  @Override
  final public int sortedCompareTo(int other) {
    assert sortedIndex > SORTED_INDEX_UNKNOWN;
    assert other > SORTED_INDEX_UNKNOWN;
    return Integer.compare(sortedIndex, other);
  }

  @Override
  public void flushCachedValues() {
    super.flushCachedValues();
    resetSortedIndex();
  }

  public void resetSortedIndex() {
    sortedIndex = SORTED_INDEX_UNKNOWN;
  }
}
