/*
 * Copyright 2014-present Facebook, Inc.
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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.tree.FieldNode;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Represents a field within a class being stubbed. This is essentially a {@link FieldNode} with the
 * difference being that it implements {@link java.lang.Comparable}.
 */
class FieldMirror extends FieldVisitor implements Comparable<FieldMirror> {

  private final String key;
  private final int access;
  private final String name;
  private final String desc;
  private final String signature;
  private final Object value;
  private final SortedSet<AnnotationMirror> annotations = new TreeSet<>();
  private final SortedSet<TypeAnnotationMirror> typeAnnotations = new TreeSet<>();

  public FieldMirror(
      int access,
      String name,
      String desc,
      String signature,
      Object value,
      FieldVisitor fieldVisitor) {
    super(Opcodes.ASM5, fieldVisitor);

    this.access = access;
    this.name = name;
    this.desc = desc;
    this.signature = signature;
    this.value = value;
    this.key = name + desc + signature;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    AnnotationMirror mirror = new AnnotationMirror(desc, visible,
        super.visitAnnotation(desc, visible));
    annotations.add(mirror);
    return mirror;
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      int typeRef, TypePath typePath, String desc, boolean visible) {
    TypeAnnotationMirror mirror = new TypeAnnotationMirror(
        typeRef,
        typePath,
        desc,
        visible,
        super.visitTypeAnnotation(typeRef, typePath, desc, visible));
    typeAnnotations.add(mirror);
    return mirror;
  }

  public void accept(ClassVisitor cv) {
    FieldVisitor fv = cv.visitField(access, name, desc, signature, value);
    if (fv == null) {
      return;
    }

    for (AnnotationMirror annotation : annotations) {
      annotation.appendTo(fv);
    }
    for (TypeAnnotationMirror typeAnnotation : typeAnnotations) {
      typeAnnotation.appendTo(fv);
    }
    fv.visitEnd();
  }

  @Override
  public int compareTo(FieldMirror o) {
    if (this == o) {
      return 0;
    }

    return key.compareTo(o.key);
  }
}
