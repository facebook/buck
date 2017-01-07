/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;

/**
 * An implementation of {@link DeclaredType} that is not dependent on any particular compiler
 * implementation. It requires {@link javax.lang.model.element.Element} and {@link TypeMirror}
 * objects, but does not depend on any particular implementation of them (beyond the spec).
 */
class StandaloneDeclaredType extends StandaloneTypeMirror implements DeclaredType {
  private final TypeElement typeElement;
  private final TypeMirror enclosingType = StandaloneNoType.KIND_NONE;
  private final List<TypeMirror> typeArguments;

  public StandaloneDeclaredType(TypeElement typeElement) {
    this(typeElement, Collections.emptyList());
  }

  public StandaloneDeclaredType(TypeElement typeElement, List<TypeMirror> typeArguments) {
    super(TypeKind.DECLARED);
    this.typeElement = typeElement;
    this.typeArguments =
        Collections.unmodifiableList(new ArrayList<>(typeArguments));
  }

  @Override
  public Element asElement() {
    return typeElement;
  }

  @Override
  public TypeMirror getEnclosingType() {
    return enclosingType;
  }

  @Override
  public List<? extends TypeMirror> getTypeArguments() {
    return typeArguments;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();

    builder.append(typeElement.toString());
    if (!typeArguments.isEmpty()) {
      builder.append('<');
      for (int i = 0; i < typeArguments.size(); i++) {
        if (i > 0) {
          builder.append(',');
        }
        builder.append(typeArguments.get(i).toString());
      }
      builder.append('>');
    }
    return builder.toString();
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }
}
