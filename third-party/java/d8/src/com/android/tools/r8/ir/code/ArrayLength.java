// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;

public class ArrayLength extends Instruction {

  public ArrayLength(Value dest, Value array) {
    super(dest, array);
  }

  public Value dest() {
    return outValue;
  }

  public Value array() {
    return inValues.get(0);
  }

  @Override
  public boolean isArrayLength() {
    return true;
  }

  @Override
  public ArrayLength asArrayLength() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    int array = builder.allocatedRegister(array(), getNumber());
    builder.add(this, new com.android.tools.r8.code.ArrayLength(dest, array));
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean identicalAfterRegisterAllocation(Instruction other, RegisterAllocator allocator) {
    if (super.identicalAfterRegisterAllocation(other, allocator)) {
      // The array length instruction doesn't carry the element type. The art verifier doesn't
      // allow an array length instruction into which arrays of two different base types can
      // flow. Therefore, as a safe approximation we only consider array length instructions
      // equal when they have the same inflowing SSA value.
      // TODO(ager): We could perform conservative type propagation earlier in the pipeline and
      // add a member type to array length instructions.
      return array() == other.asArrayLength().array();
    }
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isArrayLength();
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isArrayLength();
    return 0;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }
}
