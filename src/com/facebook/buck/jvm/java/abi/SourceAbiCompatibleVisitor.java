/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * This class fixes up a few details of class ABIs so that they match the way source ABIs generate
 * the same details. It allows us to take potentially risky shortcuts in source ABIs without losing
 * the ability to verify them by binary comparison against class ABIs.
 */
public class SourceAbiCompatibleVisitor extends ClassVisitor {
  @Nullable private String name;

  public SourceAbiCompatibleVisitor(ClassVisitor cv) {
    super(Opcodes.ASM5, cv);
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    this.name = name;
    access = stripAbstractFromEnums(access);
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  @Nullable
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    if ((access & Opcodes.ACC_BRIDGE) != 0) {
      return null;
    }
    return super.visitMethod(access, name, desc, signature, exceptions);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    Preconditions.checkNotNull(this.name);
    if (!this.name.equals(name) && !this.name.equals(outerName)) {
      // Because we can't know the flags for inferred types, InnerClassesTable marks all entries
      // as ACC_STATIC except for the class itself and its member classes. It could technically
      // use the correct flags for non-inferred types, but then it becomes impossible for us to
      // fix up the class ABI to match.
      access = Opcodes.ACC_STATIC;
    }
    access = stripAbstractFromEnums(access);
    super.visitInnerClass(name, outerName, innerName, access);
  }

  private static int stripAbstractFromEnums(int access) {
    if ((access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
      access = access & ~Opcodes.ACC_ABSTRACT;
    }
    return access;
  }
}
