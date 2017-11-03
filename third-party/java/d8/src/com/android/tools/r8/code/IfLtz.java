// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.code.If.Type;

public class IfLtz extends Format21t {

  public static final int OPCODE = 0x3a;
  public static final String NAME = "IfLtz";
  public static final String SMALI_NAME = "if-ltz";

  IfLtz(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IfLtz(int register, int offset) {
    super(register, offset);
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
    return Type.LT;
  }
}
