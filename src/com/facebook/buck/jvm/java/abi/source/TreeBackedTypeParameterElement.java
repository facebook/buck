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
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link TypeParameterElement} that uses only the information available from a
 * {@link TypeParameterTree}. This results in an incomplete implementation; see documentation for
 * individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeParameterElement extends TreeBackedElement implements TypeParameterElement {
  private final TypeParameterElement underlyingElement;
  private final StandaloneTypeVariable typeVar;
  @Nullable private List<TypeMirror> bounds;

  public TreeBackedTypeParameterElement(
      TypeParameterElement underlyingElement,
      Tree tree,
      TreeBackedElement enclosingElement,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, tree, resolver);
    this.underlyingElement = underlyingElement;
    typeVar = resolver.createType(this);

    // In javac's implementation, enclosingElement does not have type parameters in the return
    // value of getEnclosedElements
  }

  @Override
  public StandaloneTypeVariable asType() {
    return typeVar;
  }

  @Override
  @Nullable
  protected ModifiersTree getModifiersTree() {
    return null;
  }

  @Override
  public Element getGenericElement() {
    // Our constructor does not allow null enclosing elements for this element type
    return Preconditions.checkNotNull(getEnclosingElement());
  }

  @Override
  public List<? extends TypeMirror> getBounds() {
    if (bounds == null) {
      bounds =
          Collections.unmodifiableList(
              underlyingElement
                  .getBounds()
                  .stream()
                  .map(getResolver()::getCanonicalType)
                  .collect(Collectors.toList()));
    }

    return bounds;
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitTypeParameter(this, p);
  }
}
