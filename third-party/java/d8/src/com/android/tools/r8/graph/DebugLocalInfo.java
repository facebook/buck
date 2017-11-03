// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.DescriptorUtils;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;

public class DebugLocalInfo {
  public enum PrintLevel {
    NONE,
    NAME,
    FULL
  }

  public static final PrintLevel PRINT_LEVEL = PrintLevel.NAME;

  public final DexString name;
  public final DexType type;
  public final DexString signature;

  public DebugLocalInfo(DexString name, DexType type, DexString signature) {
    this.name = name;
    this.type = type;
    this.signature = signature;
  }

  public static boolean localsInfoMapsEqual(
      Int2ReferenceMap<DebugLocalInfo> set0, Int2ReferenceMap<DebugLocalInfo> set1) {
    if (set0 == null) {
      return set1 == null;
    }
    if (set1 == null) {
      return false;
    }
    if (set0.keySet().size() != set1.keySet().size()) {
      return false;
    }
    for (int i : set0.keySet()) {
      if (!set0.get(i).equals(set1.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DebugLocalInfo)) {
      return false;
    }
    DebugLocalInfo o = (DebugLocalInfo) other;
    return name == o.name && type == o.type && signature == o.signature;
  }

  @Override
  public int hashCode() {
    int hash = 7 * name.hashCode() + 13 * type.hashCode();
    if (signature != null) {
      hash += 31 * signature.hashCode();
    }
    return hash;
  }

  @Override
  public String toString() {
    switch (PRINT_LEVEL) {
      case NONE:
        return "";
      case NAME:
        return name.toString();
      case FULL:
        return name + ":" + (signature == null
            ? type
            : DescriptorUtils.descriptorToJavaType(signature.toString()));
      default:
        throw new Unreachable();
    }
  }
}
