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

class FirstOrderVisitorContext {

  public final FirstOrderTypeInfo.Builder builder = FirstOrderTypeInfo.builder();
  public final FirstOrderClassVisitor classVisitor;
  public final FirstOrderFieldVisitor fieldVisitor;
  public final FirstOrderMethodVisitor methodVisitor;
  public final FirstOrderAnnotationVisitor annotationVisitor;

  FirstOrderVisitorContext() {
    classVisitor = new FirstOrderClassVisitor(this);
    fieldVisitor = new FirstOrderFieldVisitor(this);
    methodVisitor = new FirstOrderMethodVisitor(this);
    annotationVisitor = new FirstOrderAnnotationVisitor(this);
  }
}
