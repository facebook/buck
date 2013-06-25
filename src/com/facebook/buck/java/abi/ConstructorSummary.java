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

import static javax.lang.model.element.ElementKind.CONSTRUCTOR;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

class ConstructorSummary implements Renderable {

  private final String summary;

  public ConstructorSummary(ExecutableElement element) {
    if (element.getKind() != CONSTRUCTOR) {
      throw new IllegalArgumentException("Element is not a constructor: " + element);
    }

    StringBuilder builder = new StringBuilder();
    builder.append(Annotations.printAnnotations(element.getAnnotationMirrors()));
    builder.append(Modifiers.printModifiers(element.getModifiers()));

    // The enclosing element must be a TypeElement by definition.
    TypeElement parentType = (TypeElement) element.getEnclosingElement();
    builder.append(parentType.getSimpleName());

    builder.append("(");
    new Parameters(element.getParameters()).appendTo(builder);
    builder.append(")");

    summary = builder.toString();
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append(summary).append("\n");
  }
}
