// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.conversion.IRBuilder;

public class Goto16 extends Format20t {

  public static final int OPCODE = 0x29;
  public static final String NAME = "Goto16";
  public static final String SMALI_NAME = "goto/16";

  Goto16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public Goto16(int AAAA) {
    super(AAAA);
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
  public int[] getTargets() {
    return new int[]{AAAA};
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addGoto(getOffset() + AAAA);
  }
}
