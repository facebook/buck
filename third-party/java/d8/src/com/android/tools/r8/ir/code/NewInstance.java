// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class NewInstance extends Instruction {

  public final DexType clazz;

  public NewInstance(DexType clazz, Value dest) {
    super(dest);
    dest.markNeverNull();
    assert clazz != null;
    this.clazz = clazz;
  }

  public Value dest() {
    return outValue;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    builder.add(this, new com.android.tools.r8.code.NewInstance(dest, clazz));
  }

  @Override
  public String toString() {
    return super.toString() + " " + clazz;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asNewInstance().clazz == clazz;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return clazz.slowCompareTo(other.asNewInstance().clazz);
  }

  @Override
  public int maxInValueRegister() {
    assert false : "NewInstance has no register arguments";
    return 0;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    // Creating a new instance can throw if the type is not found, or on out-of-memory.
    return true;
  }

  @Override
  public boolean isNewInstance() {
    return true;
  }

  @Override
  public NewInstance asNewInstance() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, clazz, info);
  }
}
