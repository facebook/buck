// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class XorLong extends Format23x {

  public static final int OPCODE = 0xA2;
  public static final String NAME = "XorLong";
  public static final String SMALI_NAME = "xor-long";

  XorLong(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public XorLong(int dest, int left, int right) {
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
    builder.addXor(NumericType.LONG, AA, BB, CC);
  }
}
