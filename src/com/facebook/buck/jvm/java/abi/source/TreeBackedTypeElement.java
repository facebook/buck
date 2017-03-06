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
import com.sun.source.util.TreePath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.ElementVisitor;
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
class TreeBackedTypeElement extends TreeBackedElement implements TypeElement {
  private final TypeElement underlyingElement;
  private final ClassTree tree;
  private final List<TreeBackedTypeParameterElement> typeParameters = new ArrayList<>();
  private StandaloneDeclaredType typeMirror;
  @Nullable
  private TypeMirror superclass;

  TreeBackedTypeElement(
      TypeElement underlyingElement,
      TreeBackedElement enclosingElement,
      TreePath path,
      TypeResolverFactory resolverFactory) {
    super(underlyingElement, enclosingElement, path, resolverFactory);
    this.underlyingElement = underlyingElement;
    this.tree = (ClassTree) path.getLeaf();
    typeMirror = new StandaloneDeclaredType(this);
    enclosingElement.addEnclosedElement(this);
  }

  /* package */ void addTypeParameter(TreeBackedTypeParameterElement typeParameter) {
    typeParameters.add(typeParameter);
  }

  @Override
  public TreeBackedElement getEnclosingElement() {
    return Preconditions.checkNotNull(super.getEnclosingElement());
  }

  @Override
  public NestingKind getNestingKind() {
    return underlyingElement.getNestingKind();
  }

  @Override
  public Name getQualifiedName() {
    return underlyingElement.getQualifiedName();
  }

  @Override
  public StandaloneDeclaredType asType() {
    return typeMirror;
  }

  @Override
  public TypeMirror getSuperclass() {
    if (superclass == null) {
      TypeResolver resolver = getResolver();
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

    return superclass;
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
}
