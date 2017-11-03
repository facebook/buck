// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.code.InvokeVirtualRange;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import java.util.List;
import org.objectweb.asm.Opcodes;

public class InvokeVirtual extends InvokeMethodWithReceiver {

  public InvokeVirtual(DexMethod target, Value result, List<Value> arguments) {
    super(target, result, arguments);
  }

  @Override
  public Type getType() {
    return Type.VIRTUAL;
  }

  @Override
  protected String getTypeString() {
    return "Virtual";
  }

  @Override
  public DexEncodedMethod computeSingleTarget(AppInfoWithSubtyping appInfo) {
    return appInfo.lookupSingleVirtualTarget(getInvokedMethod());
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeVirtualRange(firstRegister, argumentRegisters, getInvokedMethod());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.android.tools.r8.code.InvokeVirtual(
          argumentRegistersCount,
          getInvokedMethod(),
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
    if (!other.isInvokeVirtual()) {
      return false;
    }
    return super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeVirtual() {
    return true;
  }

  @Override
  public InvokeVirtual asInvokeVirtual() {
    return this;
  }

  @Override
  DexEncodedMethod lookupTarget(AppInfo appInfo) {
    DexMethod method = getInvokedMethod();
    return appInfo.lookupVirtualTarget(method.holder, method);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfInvoke(Opcodes.INVOKEVIRTUAL, getInvokedMethod()));
  }
}
