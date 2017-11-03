// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.MoveObject;
import com.android.tools.r8.code.MoveObjectFrom16;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class CheckCast extends Instruction {

  private final DexType type;

  // A CheckCast dex instruction takes only one register containing a value and changes
  // the associated type information for that value. In the IR we let the CheckCast
  // instruction define a new value. During register allocation we then need to arrange it
  // so that the source and destination are assigned the same register.
  public CheckCast(Value dest, Value value, DexType type) {
    super(dest, value);
    if (value.isNeverNull()) {
      dest.markNeverNull();
    }
    this.type = type;
  }

  public DexType getType() {
    return type;
  }

  public Value object() {
    return inValues().get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // The check cast instruction in dex doesn't write a new register. Therefore,
    // if the register allocator could not put input and output in the same register
    // we have to insert a move before the check cast instruction.
    int inRegister = builder.allocatedRegister(inValues.get(0), getNumber());
    int outRegister = builder.allocatedRegister(outValue, getNumber());
    if (inRegister == outRegister) {
      builder.add(this, new com.android.tools.r8.code.CheckCast(outRegister, type));
    } else {
      com.android.tools.r8.code.CheckCast cast = new com.android.tools.r8.code.CheckCast(outRegister, type);
      if (outRegister <= Constants.U4BIT_MAX && inRegister <= Constants.U4BIT_MAX) {
        builder.add(this, new com.android.tools.r8.code.Instruction[]{
            new MoveObject(outRegister, inRegister), cast});
      } else {
        builder.add(this, new com.android.tools.r8.code.Instruction[]{
            new MoveObjectFrom16(outRegister, inRegister), cast});
      }
    }
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asCheckCast().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.slowCompareTo(other.asCheckCast().type);
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
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
  public boolean isCheckCast() {
    return true;
  }

  @Override
  public CheckCast asCheckCast() {
    return this;
  }

  @Override
  public String toString() {
    return super.toString() + "; " + type;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, type, info);
  }
}
