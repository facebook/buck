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

import com.google.common.collect.ImmutableSortedSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.InnerClassNode;

/**
 * A {@link ClassVisitor} that records references to other classes. This is intended to be driven by
 * another {@link ClassVisitor} which is filtering down to just the ABI of the class.
 */
class ClassReferenceTracker extends ClassVisitor {
  private Set<String> referencedClassNames = new HashSet<>();
  @Nullable private String className;

  private final Map<String, InnerClassNode> innerClasses = new HashMap<>();

  public ClassReferenceTracker() {
    super(Opcodes.ASM7);
  }

  public ClassReferenceTracker(ClassVisitor cv) {
    super(Opcodes.ASM7, cv);
  }

  public SortedSet<String> getReferencedClassNames() {
    return ImmutableSortedSet.copyOf(referencedClassNames);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    if (name.equals(className) && outerName != null) {
      // If this class is an inner class, its outer class is considered referenced automatically
      addReferencedClassName(outerName);
    }
    innerClasses.put(name, new InnerClassNode(name, outerName, innerName, access));
    super.visitInnerClass(name, outerName, innerName, access);
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    className = name;
    if (superName != null) {
      addReferencedClassName(superName);
    }
    addReferencedClassNames(interfaces);
    visitSignature(signature);

    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public void visitEnd() {
    // If we reference inner classes, we must also reference their outer class(es).
    Set<String> newSet = new HashSet<>();
    for (String referencedClassName : referencedClassNames) {
      newSet.add(referencedClassName);
      InnerClassNode innerClassNode = innerClasses.get(referencedClassName);
      while (innerClassNode != null) {
        newSet.add(innerClassNode.name);
        innerClassNode = innerClasses.get(innerClassNode.outerName);
      }
    }
    referencedClassNames = newSet;
    super.visitEnd();
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    visitDescriptor(desc);
    return new TrackingAnnotationVisitor(api, super.visitAnnotation(desc, visible));
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      int typeRef, TypePath typePath, String desc, boolean visible) {
    visitDescriptor(desc);
    return new TrackingAnnotationVisitor(
        api, super.visitTypeAnnotation(typeRef, typePath, desc, visible));
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    if ((access & Opcodes.ACC_ENUM) != 0) {
      // Enum values don't count as references
      return super.visitField(access, name, desc, signature, value);
    }

    visitDescriptor(desc);
    visitSignature(signature);

    return new TrackingFieldVisitor(api, super.visitField(access, name, desc, signature, value));
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    visitType(Type.getMethodType(desc));
    visitSignature(signature);
    addReferencedClassNames(exceptions);

    return new TrackingMethodVisitor(
        api, super.visitMethod(access, name, desc, signature, exceptions));
  }

  void visitDescriptor(String desc) {
    visitType(Type.getType(desc));
  }

  private void visitTypes(Type[] types) {
    for (Type type : types) {
      visitType(type);
    }
  }

  private void visitType(Type type) {
    switch (type.getSort()) {
      case Type.ARRAY:
        visitType(type.getElementType());
        break;
      case Type.METHOD:
        visitType(type.getReturnType());
        visitTypes(type.getArgumentTypes());
        break;
      case Type.OBJECT:
        addReferencedClassName(type.getInternalName());
        break;
        // $CASES-OMITTED
      default:
        // Others are primitives or void; don't care
        break;
    }
  }

  void addReferencedClassName(String className) {
    referencedClassNames.add(className);
  }

  void addReferencedClassNames(@Nullable String[] classNames) {
    if (classNames == null) {
      return;
    }

    for (String className : classNames) {
      addReferencedClassName(className);
    }
  }

  private void visitSignature(@Nullable String signature) {
    if (signature == null) {
      return;
    }

    SignatureReader reader = new SignatureReader(signature);
    reader.accept(new TrackingSignatureVisitor(api));
  }

  private class TrackingMethodVisitor extends MethodVisitor {
    public TrackingMethodVisitor(int api, MethodVisitor mv) {
      super(api, mv);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(api, super.visitAnnotation(desc, visible));
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(
          api, super.visitTypeAnnotation(typeRef, typePath, desc, visible));
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(
          api, super.visitParameterAnnotation(parameter, desc, visible));
    }
  }

  private class TrackingFieldVisitor extends FieldVisitor {
    public TrackingFieldVisitor(int api, FieldVisitor fv) {
      super(api, fv);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(api, super.visitAnnotation(desc, visible));
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(
          api, super.visitTypeAnnotation(typeRef, typePath, desc, visible));
    }
  }

  private class TrackingAnnotationVisitor extends AnnotationVisitor {
    public TrackingAnnotationVisitor(int api, AnnotationVisitor av) {
      super(api, av);
    }

    @Override
    public void visit(String name, Object value) {
      if (value instanceof Type) {
        visitDescriptor(((Type) value).getDescriptor());
      }
      super.visit(name, value);
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      visitDescriptor(desc);
      super.visitEnum(name, desc, value);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return new TrackingAnnotationVisitor(api, super.visitArray(name));
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      visitDescriptor(desc);
      return new TrackingAnnotationVisitor(api, super.visitAnnotation(name, desc));
    }
  }

  private class TrackingSignatureVisitor extends SignatureVisitor {
    public TrackingSignatureVisitor(int api) {
      super(api);
    }

    @Override
    public SignatureVisitor visitClassBound() {
      return this;
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
      return this;
    }

    @Override
    public SignatureVisitor visitSuperclass() {
      return this;
    }

    @Override
    public SignatureVisitor visitInterface() {
      return this;
    }

    @Override
    public SignatureVisitor visitParameterType() {
      return this;
    }

    @Override
    public SignatureVisitor visitReturnType() {
      return this;
    }

    @Override
    public SignatureVisitor visitExceptionType() {
      return this;
    }

    @Override
    public SignatureVisitor visitArrayType() {
      return this;
    }

    @Override
    public void visitClassType(String name) {
      addReferencedClassName(name);
    }

    @Override
    public SignatureVisitor visitTypeArgument(char wildcard) {
      return this;
    }
  }
}
