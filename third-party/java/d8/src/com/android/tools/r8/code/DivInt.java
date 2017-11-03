// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class DivInt extends Format23x {

  public static final int OPCODE = 0x93;
  public static final String NAME = "DivInt";
  public static final String SMALI_NAME = "div-int";

  /*package*/ DivInt(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public DivInt(int dest, int left, int right) {
    super(dest, left, right);
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
    builder.addDiv(NumericType.INT, AA, BB, CC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
