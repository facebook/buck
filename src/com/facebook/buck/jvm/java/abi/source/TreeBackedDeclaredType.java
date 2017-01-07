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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;

/**
 * An implementation of {@link DeclaredType} that uses only the information available in one or
 * more {@link javax.lang.model.element.Element} objects. The implementation of the
 * {@link javax.lang.model.element.Element} does not matter; it can be tree-backed or from
 * the compiler.
 */
class TreeBackedDeclaredType extends TreeBackedTypeMirror implements DeclaredType {
  private final TreeBackedTypeElement typeElement;
  private final TypeMirror enclosingType = TreeBackedNoType.KIND_NONE;
  private final List<TypeMirror> typeArguments = Collections.unmodifiableList(new ArrayList<>());

  public TreeBackedDeclaredType(TreeBackedTypeElement typeElement) {
    this.typeElement = typeElement;
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
  public TypeKind getKind() {
    return TypeKind.DECLARED;
  }

  @Override
  public String toString() {
    return typeElement.toString();
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }
}
