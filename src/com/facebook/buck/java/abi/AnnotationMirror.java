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

package com.facebook.buck.java.abi;

import com.google.common.collect.Maps;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.SortedMap;

import javax.annotation.Nullable;

class AnnotationMirror extends AnnotationVisitor implements Comparable<AnnotationMirror> {
  private final SortedMap<String, AnnotationValueMirror> values;
  private final String desc;
  private final boolean visible;

  public AnnotationMirror(String desc, boolean visible) {
    super(Opcodes.ASM5);

    this.desc = desc;
    this.visible = visible;
    this.values = Maps.newTreeMap();
  }

  @Override
  public void visit(String name, Object value) {
    this.values.put(name, AnnotationValueMirror.forPrimitive(value));
  }

  @Override
  public void visitEnum(String name, String desc, String value) {
    this.values.put(name, AnnotationValueMirror.forEnum(desc, value));
  }

  @Override
  public AnnotationVisitor visitArray(String name) {
    AnnotationValueMirror array = AnnotationValueMirror.forArray();
    this.values.put(name, array);
    return array;  // Caller will use this to fill in the array
  }

  @Override
  public AnnotationVisitor visitAnnotation(String name, String desc) {
    AnnotationMirror annotation = new AnnotationMirror(desc, true);
    this.values.put(name, AnnotationValueMirror.forAnnotation(annotation));
    return annotation;
  }

  public void appendTo(AnnotationVisitor annotation, @Nullable String name) {
    AnnotationVisitor visitor = annotation.visitAnnotation(name, desc);
    visitValues(visitor);
    visitor.visitEnd();
  }

  public void appendTo(ClassWriter writer) {
    AnnotationVisitor visitor = writer.visitAnnotation(desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  @Override
  public int compareTo(AnnotationMirror o) {
    return desc.compareTo(o.desc);
  }

  public void appendTo(MethodVisitor method) {
    AnnotationVisitor visitor = method.visitAnnotation(desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  public void appendTo(MethodVisitor method, int parameterIndex) {
    AnnotationVisitor visitor = method.visitParameterAnnotation(parameterIndex, desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  public void appendTo(FieldVisitor field) {
    AnnotationVisitor visitor = field.visitAnnotation(desc, visible);
    visitValues(visitor);
    visitor.visitEnd();
  }

  private void visitValues(AnnotationVisitor visitor) {
    for (Map.Entry<String, AnnotationValueMirror> entry : values.entrySet()) {
      entry.getValue().accept(entry.getKey(), visitor);
    }
  }
}
