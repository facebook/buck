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
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

class FirstOrderFieldVisitor extends FieldVisitor {

  private final FirstOrderVisitorContext mContext;
  // Unused by dexopt: private final FirstOrderTypeInfo.Builder mBuilder;

  public FirstOrderFieldVisitor(FirstOrderVisitorContext context) {
    super(Opcodes.ASM5);
    mContext = context;
    // Unused by dexopt: mBuilder = context.builder;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    //Unused by dexopt: mBuilder.addDependencyDesc(desc);
    return mContext.annotationVisitor;
  }
}
