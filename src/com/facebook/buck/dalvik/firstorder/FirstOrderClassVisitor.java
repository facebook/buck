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
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class FirstOrderClassVisitor extends ClassVisitor {

  private final FirstOrderVisitorContext mContext;
  private final FirstOrderTypeInfo.Builder mBuilder;

  FirstOrderClassVisitor(FirstOrderVisitorContext context) {
    super(Opcodes.ASM4);
    mContext = context;
    mBuilder = mContext.builder;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    mBuilder.setTypeInternalName(name);
    mBuilder.setSuperTypeInternalName(superName);
    for (String interfaceName : interfaces) {
      mBuilder.addInterfaceTypeInternalName(interfaceName);
    }
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
    return mContext.annotationVisitor;
  }

  @Override
  public FieldVisitor visitField(
      int access,
      String name,
      String desc,
      String signature,
      Object value) {
    mBuilder.addDependencyDesc(desc);
    mBuilder.addValue(value);
    return mContext.fieldVisitor;
  }

  @Override
  public MethodVisitor visitMethod(
      int access,
      String name,
      String desc,
      String signature,
      String[] exceptions) {
    mBuilder.addDependencyDesc(desc);
    if (exceptions != null) {
      for (String exception : exceptions) {
        mBuilder.addDependencyInternalName(exception);
      }
    }
    return mContext.methodVisitor;
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    //Unused by dexopt: mBuilder.addDependencyInternalName(name);
    //Unused by dexopt: mBuilder.addDependencyInternalName(outerName);

    // There is a question in RemappingClassAdapter.visitInnerClass about innerName,
    // but it currently does not transform innerName.
    //Unused by dexopt: mBuilder.addDependencyInternalName(innerName);
  }

  @Override
  public void visitOuterClass(String owner, String name, String desc) {
    //Unused by dexopt: mBuilder.addDependencyInternalName(owner);
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
  }
}
