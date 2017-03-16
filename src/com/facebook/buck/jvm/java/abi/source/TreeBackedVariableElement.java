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

import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

class TreeBackedVariableElement extends TreeBackedElement implements VariableElement {
  private final VariableElement underlyingElement;
  private final TypeMirror type;

  TreeBackedVariableElement(
      VariableElement underlyingElement,
      TreeBackedElement enclosingElement,
      TreePath path,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, path, resolver);
    this.underlyingElement = underlyingElement;
    VariableTree tree = (VariableTree) path.getLeaf();
    type = resolver.resolveType(this, tree.getType());
    if (underlyingElement.getKind() == ElementKind.PARAMETER) {
      ((TreeBackedExecutableElement) enclosingElement).addParameter(this);
    } else {
      enclosingElement.addEnclosedElement(this);
    }
  }

  @Override
  public TypeMirror asType() {
    return type;
  }

  @Override
  public Object getConstantValue() {
    return underlyingElement.getConstantValue();
  }
}
