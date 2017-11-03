// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.FilledNewArray;
import com.android.tools.r8.code.FilledNewArrayRange;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import java.util.List;

public class InvokeNewArray extends Invoke {

  private DexType type;

  public InvokeNewArray(DexType type, Value result, List<Value> arguments) {
    super(result, arguments);
    this.type = type;
  }

  @Override
  public DexType getReturnType() {
    return getArrayType();
  }

  public DexType getArrayType() {
    return type;
  }

  @Override
  public Type getType() {
    return Type.NEW_ARRAY;
  }

  @Override
  protected String getTypeString() {
    return "NewArray";
  }

  @Override
  public String toString() {
    return super.toString() + "; type: " + type.toSourceString();
  }

  @Override
  public DexEncodedMethod computeSingleTarget(AppInfoWithSubtyping appInfo) {
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
      instruction = new FilledNewArrayRange(firstRegister, argumentRegisters, type);
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new FilledNewArray(
          argumentRegistersCount,
          type,
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    if (!other.isInvokeNewArray()) {
      return false;
    }
    return type == other.asInvokeNewArray().type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    if (!other.isInvokeNewArray()) {
      return -1;
    }
    return type.slowCompareTo(other.asInvokeNewArray().type);
  }

  @Override
  public boolean isInvokeNewArray() {
    return true;
  }

  @Override
  public InvokeNewArray asInvokeNewArray() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.classIsVisible(holder, type, info);
  }
}
