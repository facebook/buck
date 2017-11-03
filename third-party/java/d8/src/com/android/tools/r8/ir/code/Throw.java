// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class Throw extends JumpInstruction {

  public Throw(Value exception) {
    super(null, exception);
  }

  public Value exception() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.add(this, new com.android.tools.r8.code.Throw(builder.allocatedRegister(exception(), getNumber())));
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "Throw defines no values.";
    return 0;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isThrow();
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isThrow();
    return 0;
  }

  @Override
  public boolean isThrow() {
    return true;
  }

  @Override
  public Throw asThrow() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }
}
