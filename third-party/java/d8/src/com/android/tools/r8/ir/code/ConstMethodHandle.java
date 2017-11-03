// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.ir.conversion.DexBuilder;

public class ConstMethodHandle extends ConstInstruction {

  private final DexMethodHandle methodHandle;

  public ConstMethodHandle(Value dest, DexMethodHandle methodHandle) {
    super(dest);
    dest.markNeverNull();
    this.methodHandle = methodHandle;
  }

  public Value dest() {
    return outValue;
  }

  public DexMethodHandle getValue() {
    return methodHandle;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    builder.add(this, new com.android.tools.r8.code.ConstMethodHandle(dest, methodHandle));
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asConstMethodHandle().methodHandle == methodHandle;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return methodHandle.slowCompareTo(other.asConstMethodHandle().methodHandle);
  }

  @Override
  public int maxInValueRegister() {
    assert false : "ConstMethodHandle has no register arguments.";
    return 0;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public String toString() {
    return super.toString() + " \"" + methodHandle + "\"";
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean isOutConstant() {
    return true;
  }

  @Override
  public boolean isConstMethodHandle() {
    return true;
  }

  @Override
  public ConstMethodHandle asConstMethodHandle() {
    return this;
  }
}
