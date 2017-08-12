// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.MoveType;

// Value that has a fixed register allocated. These are used for inserting spill, restore, and phi
// moves in the spilling register allocator.
public class FixedRegisterValue extends Value {
  private final int register;

  public FixedRegisterValue(MoveType type, int register) {
    // Set local info to null since these values are never representatives of live-ranges.
    super(-1, type.toValueType(), null);
    setNeedsRegister(true);
    this.register = register;
  }

  public int getRegister() {
    return register;
  }

  @Override
  public boolean isFixedRegisterValue() {
    return true;
  }

  @Override
  public FixedRegisterValue asFixedRegisterValue() {
    return this;
  }

  @Override
  public boolean isConstant() {
    return false;
  }

  @Override
  public String toString() {
    return "r" + register;
  }
}
