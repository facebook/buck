// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class IgetObject extends Format22c {

  public static final int OPCODE = 0x54;
  public static final String NAME = "IgetObject";
  public static final String SMALI_NAME = "iget-object";

  /*package*/ IgetObject(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getFieldMap());
  }

  public IgetObject(int destRegister, int objectRegister, DexField field) {
    super(destRegister, objectRegister, field);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public DexField getField() {
    return (DexField) CCCC;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerInstanceFieldRead(getField());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInstanceGet(A, B, getField());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
