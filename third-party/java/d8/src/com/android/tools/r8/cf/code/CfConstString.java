// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.graph.DexString;
import org.objectweb.asm.MethodVisitor;

public class CfConstString extends CfInstruction {

  private final DexString string;

  public CfConstString(DexString string) {
    this.string = string;
  }

  @Override
  public void write(MethodVisitor visitor) {
    visitor.visitLdcInsn(string.toString());
  }
}
