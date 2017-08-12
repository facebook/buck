// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.ValueType;
import org.objectweb.asm.MethodVisitor;

public class CfConstNumber extends CfInstruction {

  private final long value;
  private final ValueType type;

  public CfConstNumber(long value, ValueType type) {
    this.value = value;
    this.type = type;
  }

  @Override
  public void write(MethodVisitor visitor) {
    switch (type) {
      case INT:
        visitor.visitLdcInsn((int)value);
        break;
      case LONG:
        visitor.visitLdcInsn(value);
        break;
      case FLOAT:
        visitor.visitLdcInsn(Float.intBitsToFloat((int)value));
        break;
      case DOUBLE:
        visitor.visitLdcInsn(Double.longBitsToDouble(value));
        break;
      default:
        throw new Unreachable("Non supported type in cf backend: " + type);
    }
  }
}
