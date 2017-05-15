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
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link Element} that uses only the information available from a {@link
 * com.sun.source.tree.Tree}. This results in an incomplete implementation; see documentation for
 * individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
abstract class TreeBackedElement implements Element {
  private final Element underlyingElement;
  @Nullable private final TreeBackedElement enclosingElement;
  private final List<Element> enclosedElements = new ArrayList<>();
  private final TreeBackedElementResolver resolver;

  @Nullable private final Tree tree;
  @Nullable private List<TreeBackedAnnotationMirror> annotationMirrors;

  public TreeBackedElement(
      Element underlyingElement,
      @Nullable TreeBackedElement enclosingElement,
      @Nullable Tree tree,
      TreeBackedElementResolver resolver) {
    this.underlyingElement = underlyingElement;
    this.enclosingElement = enclosingElement;
    // Some element types don't appear as members of enclosingElement.getEnclosedElements, so
    // it's up to each subtype's constructor to decide whether to add itself or not.
    this.tree = tree;
    this.resolver = resolver;
  }

  /* package */ Element getUnderlyingElement() {
    return underlyingElement;
  }

  protected final TreeBackedElementResolver getResolver() {
    return resolver;
  }

  @Nullable
  /* package */ Tree getTree() {
    return tree;
  }

  @Override
  public ElementKind getKind() {
    return underlyingElement.getKind();
  }

  @Override
  public Set<Modifier> getModifiers() {
    return underlyingElement.getModifiers();
  }

  @Override
  public Name getSimpleName() {
    return underlyingElement.getSimpleName();
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
  public abstract TypeMirror asType();

  @Override
  public List<? extends AnnotationMirror> getAnnotationMirrors() {
    if (annotationMirrors == null) {
      List<? extends AnnotationMirror> underlyingAnnotations =
          underlyingElement.getAnnotationMirrors();
      if (underlyingAnnotations.isEmpty()) {
        return underlyingAnnotations;
      }

      List<? extends AnnotationTree> annotationTrees = getAnnotationTrees();
      List<TreeBackedAnnotationMirror> result = new ArrayList<>();
      for (int i = 0; i < underlyingAnnotations.size(); i++) {
        result.add(
            new TreeBackedAnnotationMirror(
                underlyingAnnotations.get(i), annotationTrees.get(i), resolver));
      }
      annotationMirrors = Collections.unmodifiableList(result);
    }
    return annotationMirrors;
  }

  private List<? extends AnnotationTree> getAnnotationTrees() {
    ModifiersTree modifiersTree = getModifiersTree();
    if (modifiersTree == null) {
      return Collections.emptyList();
    }

    return modifiersTree.getAnnotations();
  }

  @Nullable
  protected abstract ModifiersTree getModifiersTree();

  @Override
  @Nullable
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return underlyingElement.toString();
  }
}
