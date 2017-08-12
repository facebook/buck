// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class DivIntLit8 extends Format22b {

  public static final int OPCODE = 0xdb;
  public static final String NAME = "DivIntLit8";
  public static final String SMALI_NAME = "div-int/lit8";

  DivIntLit8(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public DivIntLit8(int dest, int left, int constant) {
    super(dest, left, constant);
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
  public void buildIR(IRBuilder builder) {
    builder.addDivLiteral(NumericType.INT, AA, BB, CC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
