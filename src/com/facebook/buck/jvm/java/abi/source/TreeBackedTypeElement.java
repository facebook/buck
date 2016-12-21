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

import com.sun.source.tree.ClassTree;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link TypeElement} that uses only the information available from a
 * {@link ClassTree}. This results in an incomplete implementation; see documentation for individual
 * methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeElement implements TypeElement {
  private final ClassTree tree;
  private final Name qualifiedName;

  TreeBackedTypeElement(ClassTree tree, Name qualifiedName) {
    this.tree = tree;
    this.qualifiedName = qualifiedName;
  }

  @Override
  public List<? extends Element> getEnclosedElements() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends AnnotationMirror> getAnnotationMirrors() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }

  @Override
  public NestingKind getNestingKind() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Name getQualifiedName() {
    return qualifiedName;
  }

  @Override
  public TypeMirror asType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ElementKind getKind() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<Modifier> getModifiers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Name getSimpleName() {
    return tree.getSimpleName();
  }

  @Override
  public TypeMirror getSuperclass() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends TypeMirror> getInterfaces() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends TypeParameterElement> getTypeParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Element getEnclosingElement() {
    throw new UnsupportedOperationException();
  }
}
