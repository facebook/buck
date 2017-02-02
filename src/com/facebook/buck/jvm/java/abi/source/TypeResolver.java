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
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.SimpleTreeVisitor;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves type references as {@link Tree}s to {@link TypeMirror}s.
 *
 * TODO(jkeljo): Make more notes here when we start inferring types.
 */
class TypeResolver {
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;
  private final TreeBackedScope scope;

  public TypeResolver(
      TreeBackedElements elements,
      TreeBackedTypes types,
      TreeBackedScope scope) {
    this.elements = elements;
    this.types = types;
    this.scope = scope;
  }

  /* package */ TypeMirror resolveType(Tree typeTree) {
    TypeMirror result = typeTree.accept(new SimpleTreeVisitor<TypeMirror, Void>() {
      @Override
      protected TypeMirror defaultAction(Tree node, Void aVoid) {
        throw new IllegalArgumentException(
            String.format("Unexpected tree kind: %s", node.getKind()));
      }

      @Override
      public TypeMirror visitIdentifier(IdentifierTree node, Void aVoid) {
        // TODO(jkeljo): This is a quick hack to allow references to type params. We'll need a more
        // general scoping mechanism.
        TypeElement type = scope.getEnclosingClass();
        if (type != null) {
          for (TypeParameterElement typeParameterElement : type.getTypeParameters()) {
            if (node.getName().contentEquals(typeParameterElement.getSimpleName())) {
              return typeParameterElement.asType();
            }
          }
        }

        throw new UnsupportedOperationException("Type resolution by simple name NYI");
      }

      @Override
      public TypeMirror visitTypeParameter(
          TypeParameterTree node, Void aVoid) {
        throw new UnsupportedOperationException("Type resolution for parameterized types NYI");
      }

      @Override
      @Nullable
      public TypeMirror visitMemberSelect(MemberSelectTree node, Void aVoid) {
        TypeElement typeElement = treeToTypeElement(node);
        if (typeElement == null) {
          return null;
        }

        return typeElement.asType();
      }

      @Override
      @Nullable
      public TypeMirror visitParameterizedType(ParameterizedTypeTree node, Void aVoid) {
        TypeElement typeElement = treeToTypeElement(node.getType());
        if (typeElement == null) {
          return null;
        }

        TypeMirror[] typeArgs = node.getTypeArguments()
            .stream()
            .map(TypeResolver.this::resolveType)
            .toArray(size -> new TypeMirror[size]);

        return types.getDeclaredType(typeElement, typeArgs);
      }

      @Override
      public TypeMirror visitArrayType(ArrayTypeTree node, Void aVoid) {
        TypeMirror elementType = resolveType(node.getType());

        return types.getArrayType(elementType);
      }

      @Override
      public TypeMirror visitPrimitiveType(PrimitiveTypeTree node, Void aVoid) {
        return types.getPrimitiveType(node.getPrimitiveTypeKind());
      }

      @Nullable
      private TypeElement treeToTypeElement(Tree type) {
        return elements.getTypeElement(TreeBackedTrees.treeToName(type));
      }
    }, null);

    if (result == null) {
      // Because we don't have access to the current target's dependencies, we have to assume that
      // any type name we don't know about exists in one of those. Whatever our caller does with
      // this information will need to be validated later against the full set of dependencies.
      throw new UnsupportedOperationException(
          "TODO: Need to create placeholders for unknown type names.");
    }

    return result;
  }

  /* package */ TypeMirror getJavaLangObject() {
    return Preconditions.checkNotNull(elements.getTypeElement("java.lang.Object")).asType();
  }
}
