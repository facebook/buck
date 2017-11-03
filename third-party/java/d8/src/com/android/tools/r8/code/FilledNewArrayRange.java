// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.ir.conversion.IRBuilder;

public class FilledNewArrayRange extends Format3rc {

  public static final int OPCODE = 0x25;
  public static final String NAME = "FilledNewArrayRange";
  public static final String SMALI_NAME = "filled-new-array/range";

  FilledNewArrayRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getTypeMap());
  }

  public FilledNewArrayRange(int firstContentRegister, int size, DexType type) {
    super(firstContentRegister, size, type);
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

  public DexType getType() {
    return (DexType) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) throws ApiLevelException {
    builder.addInvokeRangeNewArray(getType(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
