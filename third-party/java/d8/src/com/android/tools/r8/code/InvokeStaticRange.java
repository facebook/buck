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

public class InvokeStaticRange extends Format3rc {

  public static final int OPCODE = 0x77;
  public static final String NAME = "InvokeStaticRange";
  public static final String SMALI_NAME = "invoke-static/range";

  InvokeStaticRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getMethodMap());
  }

  public InvokeStaticRange(int firstArgumentRegister, int argumentCount, DexMethod method) {
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
    registry.registerInvokeStatic(getMethod());
  }

  @Override
  public DexMethod getMethod() {
    return (DexMethod) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addInvokeRange(Type.STATIC, getMethod(), getProto(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
