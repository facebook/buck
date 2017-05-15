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

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ModifiersTree;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link TypeElement} that uses only the information available from a {@link
 * ClassTree}. This results in an incomplete implementation; see documentation for individual
 * methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeElement extends TreeBackedParameterizable implements TypeElement {
  private final TypeElement underlyingElement;
  private final ClassTree tree;
  @Nullable private StandaloneDeclaredType typeMirror;
  @Nullable private TypeMirror superclass;
  @Nullable private List<? extends TypeMirror> interfaces;

  TreeBackedTypeElement(
      TypeElement underlyingElement,
      TreeBackedElement enclosingElement,
      ClassTree tree,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, tree, resolver);
    this.underlyingElement = underlyingElement;
    this.tree = tree;
    enclosingElement.addEnclosedElement(this);
  }

  @Override
  ClassTree getTree() {
    return tree;
  }

  @Override
  public TreeBackedElement getEnclosingElement() {
    return Preconditions.checkNotNull(super.getEnclosingElement());
  }

  @Override
  public NestingKind getNestingKind() {
    return underlyingElement.getNestingKind();
  }

  @Override
  public Name getQualifiedName() {
    return underlyingElement.getQualifiedName();
  }

  @Override
  public StandaloneDeclaredType asType() {
    if (typeMirror == null) {
      typeMirror = getResolver().createType(this);
    }
    return typeMirror;
  }

  @Override
  @Nullable
  protected ModifiersTree getModifiersTree() {
    return tree != null ? tree.getModifiers() : null;
  }

  @Override
  public TypeMirror getSuperclass() {
    if (superclass == null) {
      superclass = getResolver().getCanonicalType(underlyingElement.getSuperclass());
    }

    return superclass;
  }

  @Override
  public List<? extends TypeMirror> getInterfaces() {
    if (interfaces == null) {
      interfaces =
          Collections.unmodifiableList(
              underlyingElement
                  .getInterfaces()
                  .stream()
                  .map(getResolver()::getCanonicalType)
                  .collect(Collectors.toList()));
    }
    return interfaces;
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitType(this, p);
  }
}
