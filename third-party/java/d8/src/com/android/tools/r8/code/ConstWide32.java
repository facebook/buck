// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.code.WideConstant;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.StringUtils;

public class ConstWide32 extends Format31i implements WideConstant {

  public static final int OPCODE = 0x17;
  public static final String NAME = "ConstWide32";
  public static final String SMALI_NAME = "const-wide/32";

  ConstWide32(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ConstWide32(int dest, int constant) {
    super(dest, constant);
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
  public long decodedValue() {
    return BBBBBBBB;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", " + StringUtils.hexString(decodedValue(), 16) +
        " (" + decodedValue() + ")");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(
        "v" + AA + ", " + StringUtils.hexString(decodedValue(), 16) + "  # " + decodedValue());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addConst(ValueType.LONG_OR_DOUBLE, AA, decodedValue());
  }
}
