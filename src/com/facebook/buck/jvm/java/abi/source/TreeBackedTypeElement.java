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

import com.facebook.buck.util.exportedfiles.Nullable;
import com.facebook.buck.util.exportedfiles.Preconditions;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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
  private final ClassTree tree;
  private final Name qualifiedName;
  private StandaloneDeclaredType typeMirror;
  @Nullable
  private TypeMirror superclass;
  @Nullable
  private List<TypeParameterElement> typeParameters;

  TreeBackedTypeElement(ClassTree tree, Name qualifiedName) {
    super(tree.getSimpleName(), null);  // TODO(jkeljo): Proper enclosing element
    this.tree = tree;
    this.qualifiedName = qualifiedName;
    typeMirror = new StandaloneDeclaredType(this);
  }

  /* package */ void resolve(TreeBackedElements elements, TreeBackedTypes types) {
    resolveSuperclass(elements, types);
    resolveTypeParameters(elements, types);
  }

  private void resolveSuperclass(TreeBackedElements elements, TreeBackedTypes types) {
    final Tree extendsClause = tree.getExtendsClause();
    if (extendsClause == null) {
      if (tree.getKind() == Tree.Kind.INTERFACE) {
        superclass = StandaloneNoType.KIND_NONE;
      } else {
        superclass =
            Preconditions.checkNotNull(elements.getTypeElement("java.lang.Object")).asType();
      }
    } else {
      superclass = types.resolveType(extendsClause);
    }
  }

  private void resolveTypeParameters(TreeBackedElements elements, TreeBackedTypes types) {
    typeParameters = Collections.unmodifiableList(
        tree.getTypeParameters()
          .stream()
          .map(typeParamTree -> TreeBackedTypeParameterElement.resolveTypeParameter(
              this, typeParamTree, elements, types))
          .collect(Collectors.toList())
    );
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
  @Nullable
  public List<? extends TypeParameterElement> getTypeParameters() {
    return typeParameters;
  }

  @Override
  public String toString() {
    return getQualifiedName().toString();
  }
}
