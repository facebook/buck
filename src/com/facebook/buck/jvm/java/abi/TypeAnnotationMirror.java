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

import com.google.common.collect.ComparisonChain;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.TypePath;

class TypeAnnotationMirror extends AnnotationMirror {
  private final int typeRef;
  private final TypePath typePath;

  public TypeAnnotationMirror(
      int typeRef,
      TypePath typePath,
      String desc,
      boolean visible,
      AnnotationVisitor annotationVisitor) {
    super(desc, visible, annotationVisitor);

    this.typeRef = typeRef;
    this.typePath = typePath;
  }

  @Override
  public void appendTo(ClassWriter writer) {
    AnnotationVisitor visitor = writer.visitTypeAnnotation(typeRef, typePath, desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  @Override
  public void appendTo(MethodVisitor method) {
    AnnotationVisitor visitor = method.visitTypeAnnotation(typeRef, typePath, desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  @Override
  public void appendTo(FieldVisitor field) {
    AnnotationVisitor visitor = field.visitTypeAnnotation(typeRef, typePath, desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  @Override
  public int compareTo(AnnotationMirror am) {
    TypeAnnotationMirror o = (TypeAnnotationMirror) am;
    if (this == o) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(desc, o.desc)
        .compare(typeRef, o.typeRef)
        .result();
  }
}
