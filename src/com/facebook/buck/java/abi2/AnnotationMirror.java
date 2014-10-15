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

package com.facebook.buck.java.abi2;


import com.google.common.collect.Maps;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.SortedMap;

class AnnotationMirror extends AnnotationVisitor implements Comparable<AnnotationMirror> {
  private final SortedMap<String, String> annotations;
  private final String desc;
  private final boolean visible;

  public AnnotationMirror(String desc, boolean visible) {
    super(Opcodes.ASM5);

    this.desc = desc;
    this.visible = visible;
    this.annotations = Maps.newTreeMap();
  }

  @Override
  public AnnotationVisitor visitAnnotation(String name, String desc) {
    annotations.put(name, desc);
    return this;
  }

  public void appendTo(ClassWriter writer) {
    AnnotationVisitor visitor = writer.visitAnnotation(desc, visible);

    for (Map.Entry<String, String> entry : annotations.entrySet()) {
      visitor.visitAnnotation(entry.getKey(), entry.getValue());
    }

    visitor.visitEnd();
  }

  @Override
  public int compareTo(AnnotationMirror o) {
    return desc.compareTo(o.desc);
  }

  public void appendTo(MethodVisitor method) {
    AnnotationVisitor visitor = method.visitAnnotation(desc, visible);
    visitor.visitEnd();
  }

  public void appendTo(MethodVisitor method, int parameterIndex) {
    AnnotationVisitor visitor = method.visitParameterAnnotation(parameterIndex, desc, visible);
    visitor.visitEnd();
  }

  public void appendTo(FieldVisitor field) {
    AnnotationVisitor visitor = field.visitAnnotation(desc, visible);
    visitor.visitEnd();
  }
}
