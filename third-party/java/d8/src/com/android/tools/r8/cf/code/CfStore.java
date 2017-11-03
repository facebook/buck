// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.ValueType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfStore extends CfInstruction {

  private final int var;
  private final ValueType type;

  public CfStore(ValueType type, int var) {
    this.var = var;
    this.type = type;
  }

  private int getStoreType() {
    switch (type) {
      case OBJECT:
        return Opcodes.ASTORE;
      case INT:
        return Opcodes.ISTORE;
      case FLOAT:
        return Opcodes.FSTORE;
      case LONG:
        return Opcodes.LSTORE;
      case DOUBLE:
        return Opcodes.DSTORE;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public void write(MethodVisitor visitor) {
    visitor.visitVarInsn(getStoreType(), var);
  }
}
