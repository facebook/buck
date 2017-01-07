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

import com.facebook.buck.util.exportedfiles.Preconditions;
import com.sun.source.tree.TypeParameterTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link TypeParameterElement} that uses only the information available from a
 * {@link TypeParameterTree}. This results in an incomplete implementation; see documentation for
 * individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeParameterElement extends TreeBackedElement implements TypeParameterElement {
  private final List<TypeMirror> bounds;

  public static TypeParameterElement resolveTypeParameter(
      Element enclosingElement,
      TypeParameterTree tree,
      TreeBackedElements elements,
      TreeBackedTypes types) {
    TypeMirror[] bounds;
    if (tree.getBounds().isEmpty()) {
      bounds = new TypeMirror[] {
          Preconditions.checkNotNull(elements.getTypeElement("java.lang.Object")).asType() };
    } else {
      bounds = tree.getBounds().stream()
          .map(boundTree -> types.resolveType(boundTree))
          .toArray(size -> new TypeMirror[size]);
    }

    return new TreeBackedTypeParameterElement(tree.getName(), enclosingElement, bounds);
  }

  private TreeBackedTypeParameterElement(
      Name simpleName,
      Element enclosingElement,
      TypeMirror... bounds) {
    super(simpleName, enclosingElement);

    this.bounds = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(bounds)));
  }

  @Override
  public TypeMirror asType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Element getGenericElement() {
    // Our constructor does not allow null enclosing elements for this element type
    return Preconditions.checkNotNull(getEnclosingElement());
  }

  @Override
  public List<? extends TypeMirror> getBounds() {
    return bounds;
  }
}
