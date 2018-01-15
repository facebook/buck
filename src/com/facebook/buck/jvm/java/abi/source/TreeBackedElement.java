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
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
abstract class TreeBackedElement extends TreeBackedAnnotatedConstruct implements ArtificialElement {
  private final Element underlyingElement;
  @Nullable private final TreeBackedElement enclosingElement;
  private final List<Element> enclosedElements = new ArrayList<>();
  @Nullable private final PostEnterCanonicalizer canonicalizer;

  @Nullable private final TreePath treePath;

  public TreeBackedElement(
      Element underlyingElement,
      @Nullable TreeBackedElement enclosingElement,
      @Nullable TreePath treePath,
      @Nullable PostEnterCanonicalizer canonicalizer) {
    super(underlyingElement);
    this.underlyingElement = underlyingElement;
    this.enclosingElement = enclosingElement;
    // Some element types don't appear as members of enclosingElement.getEnclosedElements, so
    // it's up to each subtype's constructor to decide whether to add itself or not.
    this.treePath = treePath;
    this.canonicalizer = canonicalizer;
  }

  /* package */ Element getUnderlyingElement() {
    return underlyingElement;
  }

  protected final PostEnterCanonicalizer getCanonicalizer() {
    return Preconditions.checkNotNull(canonicalizer);
  }

  public abstract void complete();

  @Nullable
  /* package */ TreePath getTreePath() {
    return treePath;
  }

  @Nullable
  /* package */ Tree getTree() {
    return treePath == null ? null : treePath.getLeaf();
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

  @Override
  public void addEnclosedElement(Element element) {
    enclosedElements.add(element);
  }

  @Override
  public abstract TypeMirror asType();

  @Override
  public String toString() {
    return underlyingElement.toString();
  }
}
