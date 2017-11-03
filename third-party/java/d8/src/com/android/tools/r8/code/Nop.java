// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ir.conversion.IRBuilder;

public class Nop extends Format10x {

  public static final int OPCODE = 0x0;
  public static final String NAME = "Nop";
  public static final String SMALI_NAME = "nop";

  Nop(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public Nop() {
  }

  public static Nop create(int high, BytecodeStream stream) {
    switch (high) {
      case 0x01:
        return new PackedSwitchPayload(high, stream);
      case 0x02:
        return new SparseSwitchPayload(high, stream);
      case 0x03:
        return new FillArrayDataPayload(high, stream);
      default:
        return new Nop(high, stream);
    }
  }

  @Override
  public int hashCode() {
    return NAME.hashCode() * 7 + super.hashCode();
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
    // The IR does not represent nops.
    // Nops needed by dex, eg, for tight infinite loops, will be created upon conversion to dex.
  }
}
