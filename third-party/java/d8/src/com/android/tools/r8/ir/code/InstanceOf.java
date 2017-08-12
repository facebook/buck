// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class InstanceOf extends Instruction {

  private final DexType type;

  public InstanceOf(Value dest, Value value, DexType type) {
    super(dest, value);
    this.type = type;
  }

  public DexType type() {
    return type;
  }

  public Value dest() {
    return outValue;
  }

  public Value value() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    int value = builder.allocatedRegister(value(), getNumber());
    builder.add(this, new com.android.tools.r8.code.InstanceOf(dest, value, type));
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
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asInstanceOf().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.slowCompareTo(other.asInstanceOf().type);
  }

  @Override
  public boolean isInstanceOf() {
    return true;
  }

  @Override
  public InstanceOf asInstanceOf() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, type, info);
  }
}
