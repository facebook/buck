// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.If.Type;

public class IfGt extends Format22t {

  public static final int OPCODE = 0x36;
  public static final String NAME = "IfGt";
  public static final String SMALI_NAME = "if-gt";

  IfGt(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IfGt(int register1, int register2, int offset) {
    super(register1, register2, offset);
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
  public Type getType() {
    return Type.GT;
  }
}
