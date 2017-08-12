// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;
public class RemIntLit8 extends Format22b {

  public static final int OPCODE = 0xdc;
  public static final String NAME = "RemIntLit8";
  public static final String SMALI_NAME = "rem-int/lit8";

  RemIntLit8(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RemIntLit8(int dest, int register, int constant) {
    super(dest, register, constant);
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
    builder.addRemLiteral(NumericType.INT, AA, BB, CC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
