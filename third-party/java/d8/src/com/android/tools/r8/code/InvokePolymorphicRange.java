// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.conversion.IRBuilder;

/** An invoke-polymorphic range instruction used to call method with polymorphic signature. */
public class InvokePolymorphicRange extends Format4rcc {

  public static final int OPCODE = 0xfb;
  public static final String NAME = "InvokePolymorphicRange";
  public static final String SMALI_NAME = "invoke-polymorphic/range";

  InvokePolymorphicRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getMethodMap(), mapping.getProtosMap());
  }

  public InvokePolymorphicRange(
      int firstArgumentRegister, int argumentCount, DexMethod method, DexProto proto) {
    super(firstArgumentRegister, argumentCount, method, proto);
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
  public DexMethod getMethod() {
    return BBBB;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerInvokeDirect(getMethod());
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addInvokeRange(Type.POLYMORPHIC, getMethod(), getProto(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
