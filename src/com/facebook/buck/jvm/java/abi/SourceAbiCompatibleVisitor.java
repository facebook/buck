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

import com.facebook.infer.annotation.PropagatesNullable;
import java.util.Objects;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureWriter;

/**
 * This class fixes up a few details of class ABIs so that they match the way source ABIs generate
 * the same details. It allows us to take potentially risky shortcuts in source ABIs without losing
 * the ability to verify them by binary comparison against class ABIs.
 */
public class SourceAbiCompatibleVisitor extends ClassVisitor {

  private final AbiGenerationMode compatibilityMode;
  @Nullable private String name;

  public SourceAbiCompatibleVisitor(ClassVisitor cv, AbiGenerationMode compatibilityMode) {
    super(Opcodes.ASM7, cv);
    this.compatibilityMode = compatibilityMode;
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
    super.visit(version, access, name, fixupSignature(signature), superName, interfaces);
  }

  @Override
  @Nullable
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    if (!compatibilityMode.usesDependencies() && (access & Opcodes.ACC_BRIDGE) != 0) {
      return null;
    }

    return super.visitMethod(access, name, desc, fixupSignature(signature), exceptions);
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    return super.visitField(access, name, desc, fixupSignature(signature), value);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    Objects.requireNonNull(this.name);
    if (!compatibilityMode.usesDependencies()
        && !this.name.equals(name)
        && !this.name.equals(outerName)) {
      // Because we can't know the flags for inferred types, InnerClassesTable marks all entries
      // as ACC_STATIC except for the class itself and its member classes. It could technically
      // use the correct flags for non-inferred types, but then it becomes impossible for us to
      // fix up the class ABI to match.
      access = Opcodes.ACC_STATIC;
    }
    access = stripAbstractFromEnums(access);
    super.visitInnerClass(name, outerName, innerName, access);
  }

  private String fixupSignature(@PropagatesNullable String signature) {
    if (signature == null || compatibilityMode.usesDependencies()) {
      return signature;
    }

    SignatureReader reader = new SignatureReader(signature);
    SignatureWriter writer = new SignatureWriter();

    reader.accept(new SourceAbiCompatibleSignatureVisitor(writer));

    return writer.toString();
  }

  private int stripAbstractFromEnums(int access) {
    if (compatibilityMode.usesDependencies()) {
      return access;
    }

    if ((access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
      access = access & ~Opcodes.ACC_ABSTRACT;
    }
    return access;
  }
}
