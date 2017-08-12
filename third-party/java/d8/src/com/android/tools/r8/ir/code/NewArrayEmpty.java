// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.NewArray;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class NewArrayEmpty extends Instruction {

  public final DexType type;

  public NewArrayEmpty(Value dest, Value size, DexType type) {
    super(dest, size);
    dest.markNeverNull();
    this.type = type;
  }

  public Value dest() {
    return outValue;
  }

  public Value size() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int size = builder.allocatedRegister(size(), getNumber());
    int dest = builder.allocatedRegister(dest(), getNumber());
    builder.add(this, new NewArray(dest, size, type));
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
    // new-array throws if its size is negative, but can also potentially throw on out-of-memory.
    return true;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asNewArrayEmpty().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.slowCompareTo(other.asNewArrayEmpty().type);
  }

  @Override
  public boolean isNewArrayEmpty() {
    return true;
  }

  @Override
  public NewArrayEmpty asNewArrayEmpty() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, type, info);
  }
}
