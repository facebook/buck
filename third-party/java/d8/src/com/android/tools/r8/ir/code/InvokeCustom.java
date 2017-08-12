// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.InvokeCustomRange;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import java.util.List;

public final class InvokeCustom extends Invoke {

  private final DexCallSite callSite;

  public InvokeCustom(DexCallSite callSite, Value result, List<Value> arguments) {
    super(result, arguments);
    assert callSite != null;
    this.callSite = callSite;
  }

  @Override
  public DexType getReturnType() {
    return callSite.methodProto.returnType;
  }

  public DexCallSite getCallSite() {
    return callSite;
  }

  @Override
  public Type getType() {
    return Type.CUSTOM;
  }

  @Override
  protected String getTypeString() {
    return "Custom";
  }

  @Override
  public String toString() {
    return super.toString() + "; call site: " + callSite.toSourceString();
  }

  @Override
  public DexEncodedMethod computeSingleTarget(AppInfoWithSubtyping appInfo) {
    // Target method can not be known at compile time.
    return null;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeCustomRange(firstRegister, argumentRegisters, getCallSite());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.android.tools.r8.code.InvokeCustom(
          argumentRegistersCount,
          getCallSite(),
          individualArgumentRegisters[0], // C
          individualArgumentRegisters[1], // D
          individualArgumentRegisters[2], // E
          individualArgumentRegisters[3], // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeCustom() && callSite == other.asInvokeCustom().callSite;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isInvokeCustom();
    assert false : "Not supported";
    return 0;
  }

  @Override
  public boolean isInvokeCustom() {
    return true;
  }

  @Override
  public InvokeCustom asInvokeCustom() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.NEVER;
  }
}
