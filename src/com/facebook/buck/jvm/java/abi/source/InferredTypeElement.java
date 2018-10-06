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

import com.facebook.buck.jvm.java.abi.source.api.CannotInferException;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.TypeMirror;

/**
 * Represents a {@link javax.lang.model.element.TypeElement} whose existence has been inferred from
 * references to it in the code.
 */
class InferredTypeElement extends InferredElement implements ArtificialTypeElement {
  private final Name qualifiedName;
  private final TypeMirror typeMirror;

  protected InferredTypeElement(
      Name simpleName, Name qualifiedName, ArtificialElement enclosingElement) {
    super(simpleName, enclosingElement);
    this.qualifiedName = qualifiedName;
    typeMirror = new InferredDeclaredType(this);

    enclosingElement.addEnclosedElement(this);
  }

  @Override
  public List<ArtificialElement> getEnclosedElements() {
    throw new UnsupportedOperationException(
        String.format(
            "We cannot know the enclosed elements of an inferred type: %s.", qualifiedName));
  }

  @Override
  public ArtificialElement getEnclosingElement() {
    return Objects.requireNonNull(super.getEnclosingElement());
  }

  @Override
  public List<? extends ArtificialTypeParameterElement> getTypeParameters() {
    throw new CannotInferException("type parameters", this);
  }

  @Override
  public TypeMirror asType() {
    return typeMirror;
  }

  @Override
  public ElementKind getKind() {
    throw new CannotInferException("kind", this);
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitType(this, p);
  }

  @Override
  public NestingKind getNestingKind() {
    // We'll never need to infer local or anonymous classes, so there are only two options left
    // and we can tell the difference:
    if (getEnclosingElement() instanceof InferredTypeElement) {
      return NestingKind.MEMBER;
    }

    return NestingKind.TOP_LEVEL;
  }

  @Override
  public TypeMirror getSuperclass() {
    throw new CannotInferException("superclass", this);
  }

  @Override
  public List<? extends TypeMirror> getInterfaces() {
    throw new CannotInferException("interfaces", this);
  }

  @Override
  public Name getQualifiedName() {
    return qualifiedName;
  }

  @Override
  public String toString() {
    return qualifiedName.toString();
  }
}
