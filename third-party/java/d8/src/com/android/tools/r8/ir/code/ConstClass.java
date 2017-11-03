// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.utils.InternalOptions;

public class ConstClass extends ConstInstruction {

  private final DexType clazz;

  public ConstClass(Value dest, DexType clazz) {
    super(dest);
    this.clazz = clazz;
  }

  public Value dest() {
    return outValue;
  }

  public DexType getValue() {
    return clazz;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    builder.add(this, new com.android.tools.r8.code.ConstClass(dest, clazz));
  }

  @Override
  public int maxInValueRegister() {
    assert false : "ConstClass has no register arguments.";
    return 0;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asConstClass().clazz == clazz;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return clazz.slowCompareTo(other.asConstClass().clazz);
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    // A const-class instruction can be dead code only if the resulting program is known to contain
    // the class mentioned.
    // The simple conservative check is for the holder of the method.
    // TODO(sgjesse): It might be beneficial to check for program classes in the super hierarchy or
    // interfaces implemented.
    return code.method.method.holder == clazz;
  }

  @Override
  public boolean isConstClass() {
    return true;
  }

  @Override
  public ConstClass asConstClass() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, clazz, info);
  }
}
