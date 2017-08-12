// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.graph.DexMethod;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class CfInvoke extends CfInstruction {

  private final DexMethod method;
  private final int opcode;

  public CfInvoke(int opcode, DexMethod method) {
    assert Opcodes.INVOKEVIRTUAL <= opcode && opcode <= Opcodes.INVOKEDYNAMIC;
    this.opcode = opcode;
    this.method = method;
  }

  @Override
  public void write(MethodVisitor visitor) {
    String owner = Type.getType(method.getHolder().toDescriptorString()).getInternalName();
    String name = method.name.toString();
    String desc = method.proto.toDescriptorString();
    boolean iface = method.holder.isInterface();
    visitor.visitMethodInsn(opcode, owner, name, desc, iface);
  }
}
