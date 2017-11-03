// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Value;

public class DexEncodedField extends KeyedDexItem<DexField> {

  public static final DexEncodedField[] EMPTY_ARRAY = new DexEncodedField[]{};

  public final DexField field;
  public final FieldAccessFlags accessFlags;
  public DexAnnotationSet annotations;
  public DexValue staticValue;

  public DexEncodedField(
      DexField field,
      FieldAccessFlags accessFlags,
      DexAnnotationSet annotations,
      DexValue staticValue) {
    assert !accessFlags.isStatic() || staticValue != null;
    this.field = field;
    this.accessFlags = accessFlags;
    this.annotations = annotations;
    this.staticValue = staticValue;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    field.collectIndexedItems(indexedItems);
    annotations.collectIndexedItems(indexedItems);
    if (staticValue != null) {
      staticValue.collectIndexedItems(indexedItems);
    }
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    annotations.collectMixedSectionItems(mixedItems);
  }

  @Override
  public String toString() {
    return "Encoded field " + field;
  }

  @Override
  public String toSmaliString() {
    return field.toSmaliString();
  }

  @Override
  public String toSourceString() {
    return field.toSourceString();
  }

  @Override
  public DexField getKey() {
    return field;
  }

  public boolean hasAnnotation() {
    return !annotations.isEmpty();
  }

  // Returns a const instructions if this field is a compile time final const.
  public Instruction valueAsConstInstruction(AppInfo appInfo, Value dest) {
    // The only way to figure out whether the DexValue contains the final value
    // is ensure the value is not the default or check <clinit> is not present.
    if (accessFlags.isStatic() && accessFlags.isPublic() && accessFlags.isFinal()) {
      DexClass clazz = appInfo.definitionFor(field.getHolder());
      assert clazz != null : "Class for the field must be present";
      return staticValue.asConstInstruction(clazz.hasClassInitializer(), dest);
    }
    return null;
  }

  public DexEncodedField toRenamedField(DexString name, DexItemFactory dexItemFactory) {
    return new DexEncodedField(dexItemFactory.createField(field.clazz, field.type, name),
        accessFlags, annotations, staticValue);
  }

  public DexEncodedField toTypeSubstitutedField(DexField field) {
    if (this.field == field) {
      return this;
    }
    return new DexEncodedField(field, accessFlags, annotations, staticValue);
  }
}
