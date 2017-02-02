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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor8;

/**
 * An implementation of {@link TypeElement} that uses only the information available from a
 * {@link ClassTree}. This results in an incomplete implementation; see documentation for individual
 * methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
class TreeBackedTypeElement extends TreeBackedElement implements TypeElement {
  private final ClassTree tree;
  private final Name qualifiedName;
  private final List<TreeBackedTypeParameterElement> typeParameters = new ArrayList<>();
  private StandaloneDeclaredType typeMirror;
  @Nullable
  private TypeMirror superclass;

  TreeBackedTypeElement(
      TreeBackedElement enclosingElement,
      ClassTree tree,
      Name qualifiedName,
      TypeResolverFactory resolverFactory) {
    super(getElementKind(tree), tree.getSimpleName(), enclosingElement, resolverFactory);
    this.tree = tree;
    this.qualifiedName = qualifiedName;
    typeMirror = new StandaloneDeclaredType(this);
    enclosingElement.addEnclosedElement(this);
  }

  /* package */ void addTypeParameter(TreeBackedTypeParameterElement typeParameter) {
    typeParameters.add(typeParameter);
  }

  private static ElementKind getElementKind(ClassTree tree) {
    switch (tree.getKind()) {
      case ANNOTATION_TYPE:
        return ElementKind.ANNOTATION_TYPE;
      case CLASS:
        return ElementKind.CLASS;
      case ENUM:
        return ElementKind.ENUM;
      case INTERFACE:
        return ElementKind.INTERFACE;
      // $CASES-OMITTED$
      default:
        throw new IllegalArgumentException(String.format("Unexpected kind: %s", tree.getKind()));
    }
  }

  /* package */ void resolve() {
    // Need to resolve type parameters first, because the superclass definition might reference them
    resolveTypeParameters();
    resolveSuperclass();
    resolveInnerTypes();
  }

  private void resolveSuperclass() {
    final TypeResolver resolver = getResolver();
    final Tree extendsClause = tree.getExtendsClause();
    if (extendsClause == null) {
      if (tree.getKind() == Tree.Kind.INTERFACE) {
        superclass = StandaloneNoType.KIND_NONE;
      } else {
        superclass = resolver.getJavaLangObject();
      }
    } else {
      superclass = resolver.resolveType(extendsClause);
    }
  }

  private void resolveTypeParameters() {
    typeParameters.forEach(typeParam -> typeParam.resolve());
  }

  private void resolveInnerTypes() {
    getEnclosedElements().forEach(
        element -> element.accept(new SimpleElementVisitor8<Void, Void>() {
          @Override
          public Void visitType(TypeElement e, Void aVoid) {
            ((TreeBackedTypeElement) e).resolve();
            return null;
          }
        }, null));
  }

  @Override
  public TreeBackedElement getEnclosingElement() {
    return Preconditions.checkNotNull(super.getEnclosingElement());
  }

  @Override
  public NestingKind getNestingKind() {
    if (getEnclosingElement().getKind() == ElementKind.PACKAGE) {
      return NestingKind.TOP_LEVEL;
    }

    // Note that anonymous and local classes do not appear in tree-backed elements
    return NestingKind.MEMBER;
  }

  @Override
  public Name getQualifiedName() {
    return qualifiedName;
  }

  @Override
  public StandaloneDeclaredType asType() {
    return typeMirror;
  }

  @Override
  public TypeMirror getSuperclass() {
    return Preconditions.checkNotNull(superclass);  // Don't call this before resolving the element
  }

  @Override
  public List<? extends TypeMirror> getInterfaces() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<? extends TypeParameterElement> getTypeParameters() {
    return Collections.unmodifiableList(typeParameters);
  }

  @Override
  public <R, P> R accept(ElementVisitor<R, P> v, P p) {
    return v.visitType(this, p);
  }

  @Override
  public String toString() {
    return getQualifiedName().toString();
  }
}
