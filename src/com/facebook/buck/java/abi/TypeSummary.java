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

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.ENUM;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

class TypeSummary implements Renderable {

  private String summary;

  public TypeSummary(RenderableTypes renderables, TypeElement element) {
    StringBuilder builder = new StringBuilder();

    appendTypeSignature(builder, element);

    Set<String> nestedTypes = new TreeSet<>();
    Set<String> contained = new TreeSet<>();
    // Ordering matters for enum constants.
    Set<String> enumValues = new LinkedHashSet<>();

    // Collect the enclosed elements, keeping enclosed types separately.
    for (Element enclosed : element.getEnclosedElements()) {
      StringBuilder elementBuilder = new StringBuilder();
      Renderable renderable = renderables.deriveFor(enclosed);
      renderable.appendTo(elementBuilder);

      if (renderable instanceof TypeSummary) {
        nestedTypes.add(elementBuilder.toString());
      } else if (renderable instanceof EnumConstantSummary) {
        enumValues.add(elementBuilder.toString());
      } else {
        contained.add(elementBuilder.toString());
      }
    }

    // Append enum constants
    for (String value : enumValues) {
      builder.append(value);
    }

    // Append all methods, fields, enum values and constants
    for (String text : contained) {
      builder.append(text);
    }

    // Append all nested types.
    if (!nestedTypes.isEmpty()) {
      builder.append("-----\n");
      Joiner.on("-----\n").appendTo(builder, nestedTypes);
    }
    summary = builder.toString();
  }

  private void appendTypeSignature(StringBuilder builder, TypeElement element) {
    ElementKind kind = element.getKind();

    builder.append(Annotations.printAnnotations(element.getAnnotationMirrors()));
    // Note that this causes all interfaces to be marked as "abstract". Since we're just generating
    // a text representation of the interface and not something perfect, I'm okay with this.
    builder.append(Modifiers.printModifiers(element.getModifiers()));

    switch (kind) {
      case ANNOTATION_TYPE:
        builder.append("@interface ");
        break;

      case CLASS:
        builder.append("class ");
        break;

      case ENUM:
        builder.append("enum ");
        break;

      case INTERFACE:
        builder.append("interface ");
        break;

      // $CASES-OMITTED$
      default:
        throw new RuntimeException("Unhandled kind: " + kind);
    }
    builder.append(element.getQualifiedName());

    List<? extends TypeParameterElement> typeParams = element.getTypeParameters();
    if (!typeParams.isEmpty()) {
      builder.append("<");
      Joiner.on(", ").appendTo(builder, typeParams);
      builder.append(">");
    }

    if (kind == CLASS) {
      builder.append(" extends ").append(element.getSuperclass());
    }

    // Sort the list alphabetically.
    List<String> converted = new ArrayList<>();
    for (TypeMirror mirror : element.getInterfaces()) {
      converted.add(mirror.toString());
    }
    Collections.sort(converted);

    if (!converted.isEmpty()) {
      if (kind == CLASS || kind == ENUM) {
        builder.append(" implements ");
      } else {
        builder.append(" extends ");
      }
      Joiner.on(", ").appendTo(builder, converted);
    }
    builder.append("\n");
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append(summary);
  }
}
