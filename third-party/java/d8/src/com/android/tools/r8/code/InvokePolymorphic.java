// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.conversion.IRBuilder;

/**
 * An invoke-polymorphic instruction used to invoke a method in a MethodHandle using either
 * MethodHandle.invoke or MethodHandle.invokeExact.
 */
public class InvokePolymorphic extends Format45cc {

  public static final int OPCODE = 0xfa;
  public static final String NAME = "InvokePolymorphic";
  public static final String SMALI_NAME = "invoke-polymorphic";

  InvokePolymorphic(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getMethodMap(), mapping.getProtosMap());
  }

  public InvokePolymorphic(
      int A, DexMethod BBBB, DexProto HHHH, int C, int D, int E, int F, int G) {
    super(A, BBBB, HHHH, C, D, E, F, G);
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addInvokeRegisters(
        Type.POLYMORPHIC, getMethod(), getProto(), A, new int[] {C, D, E, F, G});
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
  public boolean canThrow() {
    return true;
  }
}
