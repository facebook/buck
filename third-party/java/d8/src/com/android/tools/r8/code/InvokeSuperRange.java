// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class InvokeSuperRange extends Format3rc {

  public static final int OPCODE = 0x75;
  public static final String NAME = "InvokeSuperRange";
  public static final String SMALI_NAME = "invoke-super/range";

  InvokeSuperRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getMethodMap());
  }

  public InvokeSuperRange(int firstArgumentRegister, int argumentCount, DexMethod method) {
    super(firstArgumentRegister, argumentCount, method);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerInvokeSuper(getMethod());
  }

  @Override
  public DexMethod getMethod() {
    return (DexMethod) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addInvokeRange(Type.SUPER, getMethod(), getProto(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
