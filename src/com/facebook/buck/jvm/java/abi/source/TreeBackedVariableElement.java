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
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

class TreeBackedVariableElement extends TreeBackedElement implements ArtificialVariableElement {
  private final VariableElement underlyingElement;

  @Nullable private final VariableTree tree;

  @Nullable private TypeMirror type;

  TreeBackedVariableElement(
      VariableElement underlyingElement,
      TreeBackedElement enclosingElement,
      @Nullable TreePath treePath,
      PostEnterCanonicalizer canonicalizer) {
    super(underlyingElement, enclosingElement, treePath, canonicalizer);
    this.underlyingElement = underlyingElement;
    this.tree = treePath == null ? null : (VariableTree) treePath.getLeaf();
    if (underlyingElement.getKind() == ElementKind.PARAMETER) {
      ((TreeBackedExecutableElement) enclosingElement).addParameter(this);
    } else {
      enclosingElement.addEnclosedElement(this);
    }
  }

  @Override
  public void complete() {
    asType();
  }

  @Override
  public List<? extends ArtificialElement> getEnclosedElements() {
    return Collections.emptyList();
  }

  @Override
  @Nullable
  VariableTree getTree() {
    return tree;
  }

  @Override
  public TypeMirror asType() {
    if (type == null) {
      type =
          getCanonicalizer()
              .getCanonicalType(
                  underlyingElement.asType(), getTreePath(), tree == null ? null : tree.getType());
    }
    return type;
  }

  @Override
  @Nullable
  public Object getConstantValue() {
    return underlyingElement.getConstantValue();
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitVariable(this, p);
  }
}
