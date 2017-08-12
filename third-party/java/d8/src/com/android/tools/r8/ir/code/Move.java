// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class Move extends Instruction {

  public Move(Value dest, Value src) {
    super(dest, src);
    if (src.isNeverNull()) {
      dest.markNeverNull();
    }
  }

  public Value dest() {
    return outValue;
  }

  public Value src() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addMove(this);
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U16BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U16BIT_MAX;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isMove();
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isMove();
    return 0;
  }

  @Override
  public String toString() {
    return super.toString() + " (" + outType() + ")";
  }

  @Override
  public boolean isOutConstant() {
    return src().isConstant();
  }

  @Override
  public ConstInstruction getOutConstantConstInstruction() {
    assert isOutConstant();
    return src().definition.getOutConstantConstInstruction();
  }

  @Override
  public boolean isMove() {
    return true;
  }

  @Override
  public Move asMove() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }
}
