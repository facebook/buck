// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.Cmp.Bias;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class CmpLong extends Format23x {

  public static final int OPCODE = 0x31;
  public static final String NAME = "CmpLong";
  public static final String SMALI_NAME = "cmp-long";

  CmpLong(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public CmpLong(int dest, int left, int right) {
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
    builder.addCmp(NumericType.LONG, Bias.NONE, AA, BB, CC);
  }
}
