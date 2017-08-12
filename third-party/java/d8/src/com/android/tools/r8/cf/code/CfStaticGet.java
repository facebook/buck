// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.graph.DexField;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class CfStaticGet extends CfInstruction {

  private final DexField field;

  public CfStaticGet(DexField field) {
    this.field = field;
  }

  @Override
  public void write(MethodVisitor visitor) {
    String owner = Type.getType(field.getHolder().toDescriptorString()).getInternalName();
    String name = field.name.toString();
    String desc = field.type.toDescriptorString();
    visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
  }
}
