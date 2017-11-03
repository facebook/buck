// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;
public class MulInt extends Format23x {

  public static final int OPCODE = 0x92;
  public static final String NAME = "MulInt";
  public static final String SMALI_NAME = "mul-int";

  MulInt(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public MulInt(int dest, int left, int right) {
    super(dest, left, right);
    // The art x86 backend had a bug that made it fail on "mul r0, r1, r0" instructions where
    // the second src register and the dst register is the same (but the first src register is
    // different). Therefore, we have to avoid generating that pattern. The bug was fixed for
    // Android M: https://android-review.googlesource.com/#/c/114932/
    assert dest != right || dest == left;
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
    builder.addMul(NumericType.INT, AA, BB, CC);
  }
}
