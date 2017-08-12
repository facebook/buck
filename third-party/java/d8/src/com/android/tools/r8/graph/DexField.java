// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.naming.NamingLens;

public class DexField extends Descriptor<DexEncodedField, DexField> implements
    PresortedComparable<DexField> {

  public final DexType clazz;
  public final DexType type;
  public final DexString name;

  DexField(DexType clazz, DexType type, DexString name) {
    this.clazz = clazz;
    this.type = type;
    this.name = name;
    if (!name.isValidFieldName()) {
      throw new CompilationError(
          "Field name '" + name.toString() + "' cannot be represented in dex format.");
    }
  }

  @Override
  public int computeHashCode() {
    return clazz.hashCode()
        + type.hashCode() * 7
        + name.hashCode() * 31;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexField) {
      DexField o = (DexField) other;
      return clazz.equals(o.clazz)
          && type.equals(o.type)
          && name.equals(o.name);
    }
    return false;
  }

  @Override
  public String toString() {
    return "Field " + type + " " + clazz + "." + name;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    if (indexedItems.addField(this)) {
      clazz.collectIndexedItems(indexedItems);
      type.collectIndexedItems(indexedItems);
      indexedItems.getRenamedName(this).collectIndexedItems(indexedItems);
    }
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public int compareTo(DexField other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexField other) {
    int result = clazz.slowCompareTo(other.clazz);
    if (result != 0) {
      return result;
    }
    result = name.slowCompareTo(other.name);
    if (result != 0) {
      return result;
    }
    return type.slowCompareTo(other.type);
  }

  @Override
  public int slowCompareTo(DexField other, NamingLens namingLens) {
    int result = clazz.slowCompareTo(other.clazz, namingLens);
    if (result != 0) {
      return result;
    }
    result = namingLens.lookupName(this).slowCompareTo(namingLens.lookupName(other));
    if (result != 0) {
      return result;
    }
    return type.slowCompareTo(other.type, namingLens);
  }

  @Override
  public int layeredCompareTo(DexField other, NamingLens namingLens) {
    int result = clazz.compareTo(other.clazz);
    if (result != 0) {
      return result;
    }
    result = namingLens.lookupName(this).compareTo(namingLens.lookupName(other));
    if (result != 0) {
      return result;
    }
    return type.compareTo(other.type);
  }

  @Override
  public boolean match(DexEncodedField entry) {
    return entry.field.name == name && entry.field.type == type;
  }

  @Override
  public DexType getHolder() {
    return clazz;
  }

  @Override
  public String toSmaliString() {
    return clazz.toSmaliString() + "->" + name + ":" + type.toSmaliString();
  }

  @Override
  public String toSourceString() {
    return type.toSourceString() + " " + clazz.toSourceString() + "." + name.toSourceString();
  }
}
