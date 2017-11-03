// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class Sget extends Format21c {

  public static final int OPCODE = 0x60;
  public static final String NAME = "Sget";
  public static final String SMALI_NAME = "sget";

  Sget(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getFieldMap());
  }

  public Sget(int AA, DexField BBBB) {
    super(AA, BBBB);
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
  public void registerUse(UseRegistry registry) {
    registry.registerStaticFieldRead(getField());
  }

  @Override
  public DexField getField() {
    return (DexField) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addStaticGet(AA, getField());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
