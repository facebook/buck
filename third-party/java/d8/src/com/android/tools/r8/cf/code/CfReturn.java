// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfReturn extends CfInstruction {

  @Override
  public void write(MethodVisitor visitor) {
    visitor.visitInsn(Opcodes.RETURN);
  }
}
