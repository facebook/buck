// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class ShrInt2Addr extends Format12x {

  public static final int OPCODE = 0xb9;
  public static final String NAME = "ShrInt2Addr";
  public static final String SMALI_NAME = "shr-int/2addr";

  ShrInt2Addr(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ShrInt2Addr(int left, int right) {
    super(left, right);
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
    builder.addShr(NumericType.INT, A, A, B);
  }
}
