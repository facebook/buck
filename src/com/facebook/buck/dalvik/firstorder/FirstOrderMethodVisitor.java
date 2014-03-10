/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.dalvik.firstorder;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class FirstOrderMethodVisitor extends MethodVisitor {

  private final FirstOrderVisitorContext mContext;
  private final FirstOrderTypeInfo.Builder mBuilder;

  public FirstOrderMethodVisitor(FirstOrderVisitorContext context) {
    super(Opcodes.ASM4);
    mContext = context;
    mBuilder = context.builder;
  }

  @Override
  public AnnotationVisitor visitAnnotationDefault() {
    return mContext.annotationVisitor;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
    return mContext.annotationVisitor;
  }

  @Override
  public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
    return mContext.annotationVisitor;
  }

  @Override
  public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
    visitFrameEntries(nLocal, local);
    visitFrameEntries(nStack, stack);
  }

  @Override
  public void visitTypeInsn(int opcode, String type) {
    mBuilder.addDependencyInternalName(type);
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String desc) {
    mBuilder.addDependencyInternalName(owner);
    mBuilder.addDependencyDesc(desc);
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String desc) {
    mBuilder.addDependencyInternalName(owner);
    mBuilder.addDependencyDesc(desc);
  }

  @Override
  public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object...bsmArgs) {
    mBuilder.addDependencyDesc(desc);
    mBuilder.addValue(bsm);
    for (Object bsmArg : bsmArgs) {
      mBuilder.addValue(bsmArg);
    }
  }

  @Override
  public void visitLdcInsn(Object cst) {
    mBuilder.addValue(cst);
  }

  @Override
  public void visitMultiANewArrayInsn(String desc, int dims) {
    mBuilder.addDependencyDesc(desc);
  }

  @Override
  public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
    mBuilder.addDependencyInternalName(type);
  }

  @Override
  public void visitLocalVariable(
      String name,
      String desc,
      String signature,
      Label start,
      Label end,
      int index) {
    mBuilder.addDependencyDesc(desc);
  }

  private void visitFrameEntries(int n, Object[] entries) {
    for (int i = 0; i < n; i++) {
      if (entries[i] instanceof String) {
        mBuilder.addDependencyInternalName((String) entries[i]);
      }
    }
  }
}
