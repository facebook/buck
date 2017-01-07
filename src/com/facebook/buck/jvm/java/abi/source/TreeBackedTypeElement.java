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
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.SimpleTreeVisitor;

import java.util.List;

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

  TreeBackedTypeElement(ClassTree tree, Name qualifiedName) {
    super(tree.getSimpleName());
    this.tree = tree;
    this.qualifiedName = qualifiedName;
    typeMirror = new StandaloneDeclaredType(this);
  }

  /* package */ void resolve(TreeBackedElements elements, TreeBackedTypes types) {
    resolveSuperclass(elements, types);
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
      superclass = resolveType(extendsClause, elements, types);
    }
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
  public List<? extends TypeParameterElement> getTypeParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return getQualifiedName().toString();
  }

  private TypeMirror resolveType(
      Tree typeTree,
      TreeBackedElements elements,
      TreeBackedTypes types) {
    return typeTree.accept(new SimpleTreeVisitor<TypeMirror, Void>() {
      @Override
      protected TypeMirror defaultAction(Tree node, Void aVoid) {
        throw new IllegalArgumentException(
            String.format("Unexpected tree kind: %s", node.getKind()));
      }

      @Override
      public TypeMirror visitIdentifier(IdentifierTree node, Void aVoid) {
        throw new UnsupportedOperationException("Type resolution by simple name NYI");
      }

      @Override
      public TypeMirror visitTypeParameter(
          TypeParameterTree node, Void aVoid) {
        throw new UnsupportedOperationException("Type resolution for parameterized types NYI");
      }

      @Override
      public TypeMirror visitMemberSelect(MemberSelectTree node, Void aVoid) {
        return treeToTypeElement(node).asType();
      }

      @Override
      public TypeMirror visitParameterizedType(ParameterizedTypeTree node, Void aVoid) {
        TypeElement typeElement = treeToTypeElement(node.getType());

        TypeMirror[] typeArgs = node.getTypeArguments()
            .stream()
            .map(tree -> resolveType(tree, elements, types))
            .toArray(size -> new TypeMirror[size]);

        return types.getDeclaredType(typeElement, typeArgs);
      }

      @Override
      public TypeMirror visitArrayType(ArrayTypeTree node, Void aVoid) {
        TypeMirror elementType = resolveType(node.getType(), elements, types);

        return types.getArrayType(elementType);
      }

      @Override
      public TypeMirror visitPrimitiveType(PrimitiveTypeTree node, Void aVoid) {
        return types.getPrimitiveType(node.getPrimitiveTypeKind());
      }

      private TypeElement treeToTypeElement(Tree type) {
        return Preconditions.checkNotNull(elements.getTypeElement(TreeResolver.treeToName(type)));
      }
    }, null);
  }

}
