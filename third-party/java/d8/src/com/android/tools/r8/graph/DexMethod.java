// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.naming.NamingLens;

public class DexMethod extends Descriptor<DexEncodedMethod, DexMethod>
    implements PresortedComparable<DexMethod> {

  public final DexType holder;
  public final DexProto proto;
  public final DexString name;

  // Caches used during processing.
  private DexEncodedMethod singleTargetCache;

  DexMethod(DexType holder, DexProto proto, DexString name) {
    this.holder = holder;
    this.proto = proto;
    this.name = name;
    if (!name.isValidMethodName()) {
      throw new CompilationError(
          "Method name '" + name.toString() + "' cannot be represented in dex format.");
    }
  }

  @Override
  public String toString() {
    return "Method " + holder + "." + name + " " + proto.toString();
  }

  public int getArity() {
    return proto.parameters.size();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addMethod(this)) {
      holder.collectIndexedItems(indexedItems);
      proto.collectIndexedItems(indexedItems);
      indexedItems.getRenamedName(this).collectIndexedItems(indexedItems);
    }
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public int computeHashCode() {
    return holder.hashCode()
        + proto.hashCode() * 7
        + name.hashCode() * 31;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexMethod) {
      DexMethod o = (DexMethod) other;
      return holder.equals(o.holder)
          && name.equals(o.name)
          && proto.equals(o.proto);
    }
    return false;
  }

  @Override
  public int compareTo(DexMethod other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexMethod other) {
    int result = holder.slowCompareTo(other.holder);
    if (result != 0) {
      return result;
    }
    result = name.slowCompareTo(other.name);
    if (result != 0) {
      return result;
    }
    return proto.slowCompareTo(other.proto);
  }

  @Override
  public int slowCompareTo(DexMethod other, NamingLens namingLens) {
    int result = holder.slowCompareTo(other.holder, namingLens);
    if (result != 0) {
      return result;
    }
    result = namingLens.lookupName(this).slowCompareTo(namingLens.lookupName(other));
    if (result != 0) {
      return result;
    }
    return proto.slowCompareTo(other.proto, namingLens);
  }

  @Override
  public int layeredCompareTo(DexMethod other, NamingLens namingLens) {
    int result = holder.compareTo(other.holder);
    if (result != 0) {
      return result;
    }
    result = namingLens.lookupName(this).compareTo(namingLens.lookupName(other));
    if (result != 0) {
      return result;
    }
    return proto.compareTo(other.proto);
  }

  @Override
  public boolean match(DexEncodedMethod entry) {
    return entry.method.name == name && entry.method.proto == proto;
  }

  @Override
  public DexType getHolder() {
    return holder;
  }

  public String qualifiedName() {
    return holder + "." + name;
  }

  @Override
  public String toSmaliString() {
    return holder.toSmaliString() + "->" + name + proto.toSmaliString();
  }

  @Override
  public String toSourceString() {
    StringBuilder builder = new StringBuilder();
    builder.append(proto.returnType.toSourceString());
    builder.append(" ");
    builder.append(holder.toSourceString());
    builder.append(".");
    builder.append(name);
    builder.append("(");
    for (int i = 0; i < getArity(); i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append(proto.parameters.values[i].toSourceString());
    }
    builder.append(")");
    return builder.toString();
  }

  synchronized public void setSingleVirtualMethodCache(DexEncodedMethod method) {
    singleTargetCache = method == null ? DexEncodedMethod.SENTINEL : method;
  }

  synchronized public boolean isSingleVirtualMethodCached() {
    return singleTargetCache != null;
  }

  synchronized public DexEncodedMethod getSingleVirtualMethodCache() {
    assert isSingleVirtualMethodCached();
    return singleTargetCache == DexEncodedMethod.SENTINEL ? null : singleTargetCache;
  }
}
