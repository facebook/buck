// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.InvokePolymorphicRange;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.InliningOracle;
import java.util.List;

public class InvokePolymorphic extends InvokeMethod {

  private final DexProto proto;

  public InvokePolymorphic(DexMethod target, DexProto proto, Value result, List<Value> arguments) {
    super(target, result, arguments);
    this.proto = proto;
  }

  @Override
  public DexType getReturnType() {
    return proto.returnType;
  }

  @Override
  public Type getType() {
    return Type.POLYMORPHIC;
  }

  @Override
  protected String getTypeString() {
    return "Polymorphic";
  }

  public DexProto getProto() {
    return proto;
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
      instruction = new InvokePolymorphicRange(
              firstRegister, argumentRegisters, getInvokedMethod(), getProto());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.android.tools.r8.code.InvokePolymorphic(
              argumentRegistersCount,
              getInvokedMethod(),
              getProto(),
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
    if (!other.isInvokePolymorphic()) {
      return false;
    }
    return proto.equals(((InvokePolymorphic) other).proto)
        && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokePolymorphic() {
    return true;
  }

  @Override
  public InvokePolymorphic asInvokePolymorphic() {
    return this;
  }

  @Override
  DexEncodedMethod lookupTarget(AppInfo appInfo) {
    // TODO(herhut): Implement lookup target for invokePolymorphic.
    return null;
  }

  @Override
  public InlineAction computeInlining(InliningOracle decider) {
    return decider.computeForInvokePolymorpic(this);
  }
}
