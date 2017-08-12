// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

public class ConstMethodType extends Format21c {

  public static final int OPCODE = 0xff;
  public static final String NAME = "ConstMethodType";
  public static final String SMALI_NAME = "const-method-type";

  ConstMethodType(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getProtosMap());
  }

  public ConstMethodType(int register, DexProto methodType) {
    super(register, methodType);
  }

  public DexProto getMethodType() {
    return (DexProto) BBBB;
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
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", \"" + BBBB.toString() + "\"");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", \"" + BBBB.toString() + "\"");
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    int index = BBBB.getOffset(mapping);
    if (index != (index & 0xffff)) {
      throw new InternalCompilerError("MethodType-index overflow.");
    }
    super.write(dest, mapping);
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addConstMethodType(AA, (DexProto) BBBB);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
