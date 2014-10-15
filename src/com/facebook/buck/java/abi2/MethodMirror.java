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

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.SortedSet;

class MethodMirror extends MethodVisitor implements Comparable<MethodMirror> {
  private final String name;
  private final String desc;
  private final String signature;
  private final int access;
  private final String[] exceptions;
  private final SortedSet<AnnotationMirror> annotations;
  private final AnnotationMirror[] parameterAnnotations;
  private final String key;

  public MethodMirror(int access, String name, String desc, String signature, String[] exceptions) {
    super(Opcodes.ASM5);

    this.access = access;
    this.name = name;
    this.desc = Preconditions.checkNotNull(desc);
    this.signature = signature;
    this.exceptions = exceptions;

    this.annotations = Sets.newTreeSet();

    int paramCount = countParameters(desc);
    this.parameterAnnotations = new AnnotationMirror[paramCount];

    this.key = name + desc + paramCount;
  }

  private int countParameters(String desc) {
    int count = 0;

    char[] chars = desc.toCharArray();

    // Find the opening parenthesis.
    int i = 0;
    while (i < chars.length && chars[i] != '(') {
      i++;
    }
    i++;  // We stopped on the '('

    // Now iterate, counting the parameter types until we find the closing parenthesis.
    for (; i < chars.length && chars[i] != ')'; i++) {
      switch (chars[i]) {
        // Base types as defined in
        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3.3)
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
          count++;
          break;

        // Array type
        case '[':
          // The array type is defined as "[ ComponentType". This means that we don't want to
          // increment the parameter count yet, as it'll happen in the next iteration through this
          // loop.
          break;

        // Class type
        case 'L':
          count++;
          // Skip forward to the next ";"
          while (chars[i] != ';') {
            i++;
          }
          break;

        default:
          throw new RuntimeException("Unknown parameter type: " + chars[i]);
      }
    }

    return count;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    AnnotationMirror mirror = new AnnotationMirror(desc, visible);
    annotations.add(mirror);
    return mirror;
  }

  @Override
  public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
    AnnotationMirror mirror = new AnnotationMirror(desc, visible);
    parameterAnnotations[parameter] = mirror;
    return mirror;
  }

  @Override
  public int compareTo(MethodMirror o) {
    return key.compareTo(o.key);
  }

  public void appendTo(ClassWriter writer) {
    MethodVisitor method = writer.visitMethod(access, name, desc, signature, exceptions);
    for (AnnotationMirror annotation : annotations) {
      annotation.appendTo(method);
    }
    for (int i = 0; i < parameterAnnotations.length; i++) {
      AnnotationMirror annotation = parameterAnnotations[i];
      if (annotation == null) {
        continue;
      }
      annotation.appendTo(method, i);
    }
    method.visitEnd();
  }
}
