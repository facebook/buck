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

import com.google.common.base.Joiner;

import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeParameterElement;


class MethodSummary implements Renderable {

  private final String summary;

  public MethodSummary(ExecutableElement element) {
    StringBuilder builder = new StringBuilder();

    builder.append(Annotations.printAnnotations(element.getAnnotationMirrors()));
    builder.append(Modifiers.printModifiers(element.getModifiers()));

    List<? extends TypeParameterElement> typeParameters = element.getTypeParameters();
    if (!typeParameters.isEmpty()) {
      builder.append("<");
      Joiner.on(", ").appendTo(builder, typeParameters);
      builder.append("> ");
    }

    builder.append(element.getReturnType());
    builder.append(" ");
    builder.append(element.getSimpleName());

    builder.append("(");
    new Parameters(element.getParameters()).appendTo(builder);
    builder.append(")\n");

    summary = builder.toString();
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append(summary);
  }
}
