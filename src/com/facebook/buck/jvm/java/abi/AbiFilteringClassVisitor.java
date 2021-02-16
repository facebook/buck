/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** A {@link ClassVisitor} that only passes to its delegate events for the class's ABI. */
class AbiFilteringClassVisitor extends ClassVisitor {

  private final List<String> methodsWithRetainedBody;
  @Nullable private final Set<String> referencedClassNames;

  @Nullable private String name;
  @Nullable private String outerName = null;
  private int classAccess;
  private int classVersion;
  private boolean hasVisibleConstructor = false;
  private Set<String> includedInnerClasses = new HashSet<>();
  private List<String> nestMembers = new ArrayList<>();
  private final boolean isKotlinClass;

  public AbiFilteringClassVisitor(
      ClassVisitor cv,
      List<String> methodsWithRetainedBody,
      @Nullable Set<String> referencedClassNames,
      boolean isKotlinClass) {
    super(Opcodes.ASM7, cv);
    this.methodsWithRetainedBody = methodsWithRetainedBody;
    this.referencedClassNames = referencedClassNames;
    this.isKotlinClass = isKotlinClass;
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
    classAccess = access;
    classVersion = version;
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    if (name.equals(this.name)) {
      this.outerName = outerName;
    }

    if (!shouldIncludeInnerClass(access, name, outerName)) {
      return;
    }

    includedInnerClasses.add(name);
    super.visitInnerClass(name, outerName, innerName, access);
  }

  @Override
  public void visitNestMember(String nestMember) {
    Preconditions.checkState(classVersion >= Opcodes.V11);
    nestMembers.add(nestMember);
  }

  @Override
  @Nullable
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    if (!shouldInclude(access)) {
      return null;
    }
    return super.visitField(access, name, desc, signature, value);
  }

  @Override
  @Nullable
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    if (methodsWithRetainedBody.contains(name)
        || (name.endsWith("$default")
            && methodsWithRetainedBody.contains(name.substring(0, name.length() - 8)))) {
      if (name.equals("<init>") && (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0) {
        hasVisibleConstructor = true;
      }

      return super.visitMethod(access, name, desc, signature, exceptions);
    }

    // Per JVMS8 2.9, "Class and interface initialization methods are invoked
    // implicitly by the Java Virtual Machine; they are never invoked directly from any
    // Java Virtual Machine instruction, but are invoked only indirectly as part of the class
    // initialization process." Thus we don't need to emit a stub of <clinit>.
    if (!shouldInclude(access) || (name.equals("<clinit>") && (access & Opcodes.ACC_STATIC) > 0)) {
      return null;
    }

    // We don't stub private constructors, but if stripping these constructors results in no
    // constructors at all, we want to include a default private constructor. This is because
    // removing all these private methods will make the class look like it has no constructors at
    // all, which is not possible. We track if this class has a public, non-synthetic constructor
    // and is not an interface or annotation to determine if a default private constructor is
    // generated when visitEnd() is called.
    if (name.equals("<init>") && (access & Opcodes.ACC_SYNTHETIC) == 0) {
      hasVisibleConstructor = true;
    }

    // Bridge methods are created by the compiler, and don't appear in source. It would be nice to
    // skip them, but they're used by the compiler to cover the fact that type erasure has occurred.
    // Normally the compiler adds these as public methods, but if you're compiling against a stub
    // produced using our ABI generator, we don't want people calling it accidentally. Oh well, I
    // guess it happens IRL too.
    //
    // Synthetic methods are also generated by the compiler, unless it's one of the methods named in
    // section 4.7.8 of the JVM spec, which are "<init>" and "Enum.valueOf()" and "Enum.values".
    // None of these are actually harmful to the ABI, so we allow synthetic methods through.
    // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.8
    return new SkipCodeMethodVisitor(
        Opcodes.ASM7, super.visitMethod(access, name, desc, signature, exceptions));
  }

  @Override
  public void visitEnd() {
    if (!hasVisibleConstructor && !isInterface(classAccess) && !isAnnotation(classAccess)) {
      String desc;
      if (isEnum(classAccess)) {
        desc =
            Type.getMethodType(
                    Type.VOID_TYPE, Type.getObjectType("java/lang/String"), Type.INT_TYPE)
                .getDescriptor();
      } else {
        desc =
            outerName == null
                ? Type.getMethodType(Type.VOID_TYPE).getDescriptor()
                : Type.getMethodType(Type.VOID_TYPE, Type.getObjectType(outerName)).getDescriptor();
      }
      super.visitMethod(Opcodes.ACC_PRIVATE, "<init>", desc, null, null);
    }

    // Filter nest members to included inner classes. Other nest members don't matter for ABI
    // purposes. Even though other members will point to this class as their nest host, this
    // asymmetry only matters at runtime when access control checks happen.
    for (String nestMember : nestMembers) {
      if (includedInnerClasses.contains(nestMember)) {
        super.visitNestMember(nestMember);
      }
    }

    super.visitEnd();
  }

  private boolean shouldIncludeInnerClass(int access, String name, @Nullable String outerName) {
    if (referencedClassNames == null || referencedClassNames.contains(name)) {
      // Either it's the first pass, and we're not filtering inner classes yet,
      // or it's the second one, and this inner class is part of the ABI and should
      // therefore be included
      return true;
    }

    String currentClassName = Objects.requireNonNull(this.name);
    if (name.equals(currentClassName)) {
      // Must always include the entry for our own class, since that's what makes it an inner class.
      return true;
    }

    boolean isAnonymousOrLocalClass = (outerName == null);
    if (isAnonymousOrLocalClass) {
      // Anonymous and local classes are never part of the ABI.
      return false;
    }

    if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == Opcodes.ACC_SYNTHETIC) {
      // Don't include synthetic classes
      return false;
    }

    return currentClassName.equals(outerName);
  }

  private boolean shouldInclude(int access) {
    if ((access & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) {
      return false;
    }

    return (isKotlinClass || (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_SYNTHETIC);
  }

  private boolean isInterface(int access) {
    return (access & Opcodes.ACC_INTERFACE) > 0;
  }

  private boolean isAnnotation(int access) {
    return (access & Opcodes.ACC_ANNOTATION) > 0;
  }

  private boolean isEnum(int access) {
    return (access & Opcodes.ACC_ENUM) > 0;
  }

  /** A {@link MethodVisitor} that replicates the behavior of {@link ClassReader#SKIP_CODE}. */
  private static class SkipCodeMethodVisitor extends MethodVisitor {
    public SkipCodeMethodVisitor(int api, MethodVisitor methodVisitor) {
      super(api, methodVisitor);
    }

    @Override
    public void visitAttribute(Attribute attribute) {}

    @Override
    public void visitCode() {}

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {}

    @Override
    public void visitInsn(int opcode) {}

    @Override
    public void visitIntInsn(int opcode, int operand) {}

    @Override
    public void visitVarInsn(int opcode, int var) {}

    @Override
    public void visitTypeInsn(int opcode, String type) {}

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}

    /** @deprecated */
    @Override
    @Deprecated
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor) {}

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {}

    @Override
    public void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments) {}

    @Override
    public void visitJumpInsn(int opcode, Label label) {}

    @Override
    public void visitLabel(Label label) {}

    @Override
    public void visitLdcInsn(Object value) {}

    @Override
    public void visitIincInsn(int var, int increment) {}

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {}

    @Override
    public void visitLocalVariable(
        String name, String descriptor, String signature, Label start, Label end, int index) {}

    @Override
    public void visitLineNumber(int line, Label start) {}

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {}
  }
}
