/*
 * Copyright 2017-present Facebook, Inc.
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
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.VariableTree;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

class TreeBackedVariableElement extends TreeBackedElement implements VariableElement {
  private final VariableElement underlyingElement;

  @Nullable private final VariableTree tree;

  @Nullable private TypeMirror type;

  TreeBackedVariableElement(
      VariableElement underlyingElement,
      TreeBackedElement enclosingElement,
      @Nullable VariableTree tree,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, tree, resolver);
    this.underlyingElement = underlyingElement;
    this.tree = tree;
    if (underlyingElement.getKind() == ElementKind.PARAMETER) {
      ((TreeBackedExecutableElement) enclosingElement).addParameter(this);
    } else {
      enclosingElement.addEnclosedElement(this);
    }
  }

  @Override
  @Nullable
  VariableTree getTree() {
    return tree;
  }

  @Override
  public TypeMirror asType() {
    if (type == null) {
      type = getResolver().getCanonicalType(underlyingElement.asType());
    }
    return type;
  }

  @Override
  @Nullable
  protected ModifiersTree getModifiersTree() {
    return tree != null ? tree.getModifiers() : null;
  }

  @Override
  public Object getConstantValue() {
    return underlyingElement.getConstantValue();
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitVariable(this, p);
  }
}
