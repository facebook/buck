/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.dalvik.firstorder;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;

class FirstOrderAnnotationVisitor extends AnnotationVisitor {

  private final FirstOrderTypeInfo.Builder mBuilder;

  public FirstOrderAnnotationVisitor(FirstOrderVisitorContext context) {
    super(Opcodes.ASM4);
    mBuilder = context.builder;
  }

  @Override
  public void visit(String name, Object value) {
    mBuilder.addValue(value);
  }

  @Override
  public void visitEnum(String name, String desc, String value) {
    mBuilder.addDependencyDesc(desc);
  }

  @Override
  public AnnotationVisitor visitAnnotation(String name, String desc) {
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
    return this;
  }

  @Override
  public AnnotationVisitor visitArray(String name) {
    return this;
  }
}
