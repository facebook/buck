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
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link ExecutableElement} that uses only the information available from a
 * {@link MethodTree}. This results in an incomplete implementation; see documentation for
 * individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedExecutableElement extends TreeBackedParameterizable implements ExecutableElement {
  private final ExecutableElement underlyingElement;
  private final List<TreeBackedVariableElement> parameters = new ArrayList<>();
  @Nullable private final MethodTree tree;

  @Nullable private TypeMirror returnType;
  @Nullable private TypeMirror receiverType;
  @Nullable private List<TypeMirror> thrownTypes;
  @Nullable private TreeBackedAnnotationValue defaultValue;
  @Nullable private StandaloneExecutableType typeMirror;

  TreeBackedExecutableElement(
      ExecutableElement underlyingElement,
      TreeBackedElement enclosingElement,
      @Nullable MethodTree tree,
      TreeBackedElementResolver resolver) {
    super(underlyingElement, enclosingElement, tree, resolver);
    this.underlyingElement = underlyingElement;
    this.tree = tree;
    enclosingElement.addEnclosedElement(this);
  }

  @Override
  @Nullable
  MethodTree getTree() {
    return tree;
  }

  @Override
  public StandaloneTypeMirror asType() {
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
  public TypeMirror getReturnType() {
    if (returnType == null) {
      returnType = getResolver().getCanonicalType(underlyingElement.getReturnType());
    }
    return returnType;
  }

  @Override
  public List<? extends VariableElement> getParameters() {
    return Collections.unmodifiableList(parameters);
  }

  /* package */ void addParameter(TreeBackedVariableElement parameter) {
    parameters.add(parameter);
  }

  @Override
  public TypeMirror getReceiverType() {
    if (receiverType == null) {
      receiverType = getResolver().getCanonicalType(underlyingElement.getReceiverType());
    }
    return receiverType;
  }

  @Override
  public boolean isVarArgs() {
    return underlyingElement.isVarArgs();
  }

  @Override
  public boolean isDefault() {
    return underlyingElement.isDefault();
  }

  @Override
  public List<? extends TypeMirror> getThrownTypes() {
    if (thrownTypes == null) {
      thrownTypes =
          Collections.unmodifiableList(
              underlyingElement
                  .getThrownTypes()
                  .stream()
                  .map(getResolver()::getCanonicalType)
                  .collect(Collectors.toList()));
    }

    return thrownTypes;
  }

  @Override
  @Nullable
  public TreeBackedAnnotationValue getDefaultValue() {
    if (defaultValue == null) {
      AnnotationValue underlyingValue = underlyingElement.getDefaultValue();
      if (underlyingValue != null) {
        defaultValue =
            new TreeBackedAnnotationValue(
                underlyingValue, Preconditions.checkNotNull(tree).getDefaultValue(), getResolver());
      }
    }
    return defaultValue;
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitExecutable(this, p);
  }
}
