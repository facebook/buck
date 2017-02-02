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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;

/**
 * An implementation of {@link Element} that uses only the information available from a
 * {@link com.sun.source.tree.Tree}. This results in an incomplete implementation; see documentation
 * for individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
abstract class TreeBackedElement implements Element {
  private final ElementKind kind;
  private final Name simpleName;
  @Nullable
  private final TreeBackedElement enclosingElement;
  private final List<Element> enclosedElements = new ArrayList<>();
  private final TypeResolverFactory resolverFactory;

  @Nullable
  private TypeResolver resolver;

  public TreeBackedElement(
      ElementKind kind,
      Name simpleName,
      @Nullable TreeBackedElement enclosingElement,
      TypeResolverFactory resolverFactory) {
    this.kind = kind;
    this.simpleName = simpleName;
    this.enclosingElement = enclosingElement;
    // Some element types don't appear as members of enclosingElement.getEnclosedElements, so
    // it's up to each subtype's constructor to decide whether to add itself or not.

    this.resolverFactory = resolverFactory;
  }

  protected final TypeResolver getResolver() {
    if (resolver == null) {
      resolver = resolverFactory.newInstance(this);
    }

    return resolver;
  }

  @Override
  public ElementKind getKind() {
    return kind;
  }

  @Override
  public Set<Modifier> getModifiers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Name getSimpleName() {
    return simpleName;
  }

  @Override
  @Nullable
  public TreeBackedElement getEnclosingElement() {
    return enclosingElement;
  }

  @Override
  public List<? extends Element> getEnclosedElements() {
    return Collections.unmodifiableList(enclosedElements);
  }

  protected void addEnclosedElement(Element element) {
    enclosedElements.add(element);
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
}
