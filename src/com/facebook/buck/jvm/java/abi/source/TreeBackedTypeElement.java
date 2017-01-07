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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.SimpleTreeVisitor;

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
  private TreeBackedDeclaredType typeMirror;
  @Nullable
  private TypeMirror superclass;

  TreeBackedTypeElement(ClassTree tree, Name qualifiedName) {
    this.tree = tree;
    this.qualifiedName = qualifiedName;
    typeMirror = new TreeBackedDeclaredType(this);
  }

  /* package */ void resolve(TreeBackedElements elements) {
    superclass = resolveType(tree.getExtendsClause(), elements);
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
  public TreeBackedDeclaredType asType() {
    return typeMirror;
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
  public Element getEnclosingElement() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return getQualifiedName().toString();
  }

  private TypeMirror resolveType(Tree extendsClause, TreeBackedElements elements) {
    if (extendsClause == null) {
      if (tree.getKind() == Tree.Kind.INTERFACE) {
        return TreeBackedNoType.KIND_NONE;
      } else {
        // TODO(jkeljo): This is wrong, but we don't have support for loading java.lang.Object yet
        return TreeBackedNoType.KIND_NONE;
      }
    }

    return extendsClause.accept(new SimpleTreeVisitor<TypeMirror, Void>() {
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
        CharSequence fullyQualifiedName = TreeResolver.expressionToName(node);
        TypeElement superclassElement = elements.getTypeElement(fullyQualifiedName);

        return superclassElement.asType();
      }
    }, null);
  }

}
