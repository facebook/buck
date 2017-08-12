// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class InstanceOf extends Format22c {

  public static final int OPCODE = 0x20;
  public static final String NAME = "InstanceOf";
  public static final String SMALI_NAME = "instance-of";

  InstanceOf(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getTypeMap());
  }

  public InstanceOf(int dest, int value, DexType type) {
    super(dest, value, type);
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
    registry.registerTypeReference(getType());
  }

  public DexType getType() {
    return (DexType) CCCC;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInstanceOf(A, B, getType());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
