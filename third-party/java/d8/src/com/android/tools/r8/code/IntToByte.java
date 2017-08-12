// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;
public class IntToByte extends Format12x {

  public static final int OPCODE = 0x8d;
  public static final String NAME = "IntToByte";
  public static final String SMALI_NAME = "int-to-byte";

  IntToByte(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IntToByte(int dest, int source) {
    super(dest, source);
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
    builder.addConversion(NumericType.BYTE, NumericType.INT, A, B);
  }
}
