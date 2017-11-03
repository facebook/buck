// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.utils.InternalOptions;

public class DebugLocalRead extends Instruction {

  public DebugLocalRead() {
    super(null);
  }

  @Override
  public boolean isDebugLocalRead() {
    return true;
  }

  @Override
  public DebugLocalRead asDebugLocalRead() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    throw new Unreachable("Unexpected attempt to emit debug-local read.");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
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
    // Reads are never dead code.
    // They should also have a non-empty set of debug values (see RegAlloc::computeDebugInfo)
    return false;
  }
}
