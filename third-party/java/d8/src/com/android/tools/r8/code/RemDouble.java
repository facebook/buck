// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class RemDouble extends Format23x {

  public static final int OPCODE = 0xaf;
  public static final String NAME = "RemDouble";
  public static final String SMALI_NAME = "rem-double";

  RemDouble(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RemDouble(int dest, int left, int right) {
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
    builder.addRem(NumericType.DOUBLE, AA, BB, CC);
  }
}
