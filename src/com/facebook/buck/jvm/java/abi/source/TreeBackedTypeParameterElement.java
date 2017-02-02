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
import com.sun.source.tree.TypeParameterTree;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

/**
 * An implementation of {@link TypeParameterElement} that uses only the information available from a
 * {@link TypeParameterTree}. This results in an incomplete implementation; see documentation for
 * individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeParameterElement extends TreeBackedElement implements TypeParameterElement {
  private final TypeParameterTree tree;
  private final TypeVariable typeVar;
  @Nullable
  private List<TypeMirror> bounds;

  public TreeBackedTypeParameterElement(
      TypeParameterTree tree,
      TreeBackedElement enclosingElement,
      TypeResolverFactory resolverFactory) {
    super(ElementKind.TYPE_PARAMETER, tree.getName(), enclosingElement, resolverFactory);

    this.tree = tree;
    typeVar = new StandaloneTypeVariable(this);

    // In javac's implementation, enclosingElement does not have type parameters in the return
    // value of getEnclosedElements
  }

  /* package */ void resolve() {
    TypeResolver resolver = getResolver();
    if (tree.getBounds().isEmpty()) {
      bounds = Collections.singletonList(resolver.getJavaLangObject());
    } else {
      bounds = Collections.unmodifiableList(
          tree.getBounds().stream()
              .map(boundTree -> resolver.resolveType(boundTree))
              .collect(Collectors.toList()));
    }
  }

  @Override
  public TypeMirror asType() {
    return typeVar;
  }

  @Override
  public Element getGenericElement() {
    // Our constructor does not allow null enclosing elements for this element type
    return Preconditions.checkNotNull(getEnclosingElement());
  }

  @Override
  public List<? extends TypeMirror> getBounds() {
    return Preconditions.checkNotNull(bounds);
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitTypeParameter(this, p);
  }

  @Override
  public String toString() {
    return getSimpleName().toString();
  }
}
