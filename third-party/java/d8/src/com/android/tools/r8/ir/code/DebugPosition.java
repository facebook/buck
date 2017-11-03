// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.CfBuilder.StackHelper;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.utils.InternalOptions;

public class DebugPosition extends Instruction {

  public DebugPosition() {
    super(null);
  }

  @Override
  public boolean isDebugPosition() {
    return true;
  }

  @Override
  public DebugPosition asDebugPosition() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addDebugPosition(this);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isDebugPosition();
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isDebugPosition();
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable();
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable();
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    return false;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, StackHelper stack) {
    // Nothing to do for positions which are not actual instructions.
  }

  @Override
  public void buildCf(CfBuilder builder) {
    // Nothing so far...
  }
}
