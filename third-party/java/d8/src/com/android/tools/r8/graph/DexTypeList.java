// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.NamingLens;
import java.util.Arrays;

public class DexTypeList extends DexItem implements Comparable<DexTypeList> {

  private static final DexTypeList theEmptyTypeList = new DexTypeList();

  public final DexType[] values;

  public static DexTypeList empty() {
    return theEmptyTypeList;
  }

  private DexTypeList() {
    this.values = new DexType[0];
  }

  public DexTypeList(DexType[] values) {
    assert values != null && values.length > 0;
    this.values = values;
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values);
  }

  @Override
  void collectIndexedItems(IndexedItemCollection indexedItems) {
    for (DexType type : values) {
      type.collectIndexedItems(indexedItems);
    }
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(this);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return (other instanceof DexTypeList)
        && Arrays.equals(values, ((DexTypeList) other).values);
  }

  public boolean isEmpty() {
    return values.length == 0;
  }

  public int size() {
    return values.length;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (DexType type : values) {
      builder.append(' ').append(type);
    }
    return builder.toString();
  }

  @Override
  public int compareTo(DexTypeList other) {
    for (int i = 0; i <= Math.min(values.length, other.values.length); i++) {
      if (i == values.length) {
        return i == other.values.length ? 0 : -1;
      } else if (i == other.values.length) {
        return 1;
      } else {
        int result = values[i].compareTo(other.values[i]);
        if (result != 0) {
          return result;
        }
      }
    }
    throw new Unreachable();
  }

  public int slowCompareTo(DexTypeList other) {
    for (int i = 0; i <= Math.min(values.length, other.values.length); i++) {
      if (i == values.length) {
        return i == other.values.length ? 0 : -1;
      } else if (i == other.values.length) {
        return 1;
      } else {
        int result = values[i].slowCompareTo(other.values[i]);
        if (result != 0) {
          return result;
        }
      }
    }
    throw new Unreachable();
  }

  public int slowCompareTo(DexTypeList other, NamingLens namingLens) {
    for (int i = 0; i <= Math.min(values.length, other.values.length); i++) {
      if (i == values.length) {
        return i == other.values.length ? 0 : -1;
      } else if (i == other.values.length) {
        return 1;
      } else {
        int result = values[i].slowCompareTo(other.values[i], namingLens);
        if (result != 0) {
          return result;
        }
      }
    }
    throw new Unreachable();
  }
}
