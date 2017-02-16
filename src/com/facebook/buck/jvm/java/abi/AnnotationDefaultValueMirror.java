/*
 * Copyright 2016-present Facebook, Inc.
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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.annotation.Nullable;

public class AnnotationDefaultValueMirror extends AnnotationVisitor {
  @Nullable private AnnotationValueMirror defaultValue;

  public AnnotationDefaultValueMirror(AnnotationVisitor inner) {
    super(Opcodes.ASM5, inner);
  }

  @Override
  public void visit(String name, Object value) {
    Preconditions.checkState(defaultValue == null);

    super.visit(name, value);
    defaultValue = AnnotationValueMirror.forPrimitive(value);
  }

  @Override
  public void visitEnum(String name, String desc, String value) {
    Preconditions.checkState(defaultValue == null);

    super.visitEnum(name, desc, value);
    defaultValue = AnnotationValueMirror.forEnum(desc, value);
  }

  @Override
  public AnnotationVisitor visitAnnotation(String name, String desc) {
    Preconditions.checkState(defaultValue == null);

    AnnotationMirror annotationMirror = new AnnotationMirror(
        desc,
        true,
        super.visitAnnotation(name, desc));

    defaultValue = AnnotationValueMirror.forAnnotation(annotationMirror, av);

    return annotationMirror;
  }

  @Override
  public AnnotationVisitor visitArray(String name) {
    Preconditions.checkState(defaultValue == null);

    defaultValue = AnnotationValueMirror.forArray(super.visitArray(name));
    return defaultValue;
  }

  public void appendTo(MethodVisitor method) {
    final AnnotationVisitor annotationVisitor = method.visitAnnotationDefault();

    Preconditions.checkNotNull(defaultValue).accept(null, annotationVisitor);
    annotationVisitor.visitEnd();
  }
}
