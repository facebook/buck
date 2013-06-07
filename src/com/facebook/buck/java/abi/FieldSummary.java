/*
 * Copyright 2013-present Facebook, Inc.
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

import javax.lang.model.element.VariableElement;

class FieldSummary implements Renderable {

  private final String summary;

  public FieldSummary(VariableElement element) {
    StringBuilder builder = new StringBuilder();

    builder.append(Annotations.printAnnotations(element.getAnnotationMirrors()));
    builder.append(Modifiers.printModifiers(element.getModifiers()));

    builder.append(element.asType())
        .append(" ")
        .append(element.getSimpleName());

    // If the field represents a compile-time constant (and therefore might be inlined) we need to
    // capture the default value.
    if (isCompileTimeConstant(element)) {
      builder.append(" = ").append(element.getConstantValue());
    }

    builder.append("\n");

    summary = builder.toString();
  }

  // Relevant sections from the JLS:
  //
  // A variable of primitive type or type String, that is final and initialized with a compile-time
  // constant expression (15.28), is called a constant variable.
  //
  // http://docs.oracle.com/javase/specs/jls/se7/html/jls-4.html#jls-4.12.4
  // Final fields and constants:
  // http://docs.oracle.com/javase/specs/jls/se7/html/jls-13.html#jls-13.4.9
  // Section 3 of "The form of a binary":
  // http://docs.oracle.com/javase/specs/jls/se7/html/jls-13.html#jls-13.1
  // And "Constant Expressions":
  // http://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.28
  //
  // TL;DR: we only need to care if the field is a primitive type or string, and the value is
  // calculated without a method invocation. Fortunately, this is encapsulated in the logic of
  // {@link VariableElement#getConstantValue()}
  private boolean isCompileTimeConstant(VariableElement element) {
    return element.getConstantValue() != null;
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append(summary);
  }
}
