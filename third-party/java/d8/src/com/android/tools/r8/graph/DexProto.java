// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.naming.NamingLens;

public class DexProto extends IndexedDexItem implements PresortedComparable<DexProto> {

  public final DexString shorty;
  public final DexType returnType;
  public final DexTypeList parameters;

  DexProto(DexString shorty, DexType returnType, DexTypeList parameters) {
    this.shorty = shorty;
    this.returnType = returnType;
    this.parameters = parameters;
  }

  @Override
  public int computeHashCode() {
    return shorty.hashCode()
        + returnType.hashCode() * 7
        + parameters.hashCode() * 31;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexProto) {
      DexProto o = (DexProto) other;
      return shorty.equals(o.shorty)
          && returnType.equals(o.returnType)
          && parameters.equals(o.parameters);
    }
    return false;
  }

  @Override
  public String toString() {
    return "Proto " + shorty + " " + returnType + " " + parameters;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addProto(this)) {
      shorty.collectIndexedItems(indexedItems);
      returnType.collectIndexedItems(indexedItems);
      parameters.collectIndexedItems(indexedItems);
    }
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public int compareTo(DexProto other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexProto other) {
    int result = returnType.slowCompareTo(other.returnType);
    if (result == 0) {
      result = parameters.slowCompareTo(other.parameters);
    }
    return result;
  }

  @Override
  public int slowCompareTo(DexProto other, NamingLens namingLens) {
    int result = returnType.slowCompareTo(other.returnType, namingLens);
    if (result == 0) {
      result = parameters.slowCompareTo(other.parameters, namingLens);
    }
    return result;
  }

  @Override
  public int layeredCompareTo(DexProto other, NamingLens namingLens) {
    int result = returnType.compareTo(other.returnType);
    if (result == 0) {
      result = parameters.compareTo(other.parameters);
    }
    return result;
  }

  @Override
  public String toSmaliString() {
    return toDescriptorString();
  }

  public String toDescriptorString() {
    StringBuilder builder = new StringBuilder();
    builder.append("(");
    for (int i = 0; i < parameters.values.length; i++) {
      builder.append(parameters.values[i].toDescriptorString());
    }
    builder.append(")");
    builder.append(returnType.toDescriptorString());
    return builder.toString();
  }
}
