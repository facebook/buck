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
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.SimpleElementVisitor8;

/**
 * A {@link TreePathScanner} that forms the scaffolding of any kind of type reference resolution
 * logic. Scans a {@link TreePath} that represents a reference to a type element and
 */
abstract class TreePathScannerForTypeResolution<R, P> extends TreePathScanner<R, P> {

  private final Trees trees;

  protected TreePathScannerForTypeResolution(Trees trees) {
    this.trees = trees;
  }

  @Override
  @Nullable
  public final R visitAnnotatedType(AnnotatedTypeTree node, P p) {
    return scan(node.getUnderlyingType(), p);
  }

  @Override
  @Nullable
  public final R visitParameterizedType(ParameterizedTypeTree node, P p) {
    return scan(node.getType(), p);
  }

  @Override
  @Nullable
  public final R visitMemberSelect(MemberSelectTree node, P p) {
    TreePath referencingPath = getCurrentPath();
    Element referencedElement = Objects.requireNonNull(trees.getElement(referencingPath));
    return resolveElement(referencingPath, referencedElement, p);
  }

  @Override
  @Nullable
  public final R visitIdentifier(IdentifierTree node, P p) {
    TreePath referencingPath = getCurrentPath();
    Element referencedElement = Objects.requireNonNull(trees.getElement(referencingPath));
    return resolveElement(referencingPath, referencedElement, p);
  }

  @Nullable
  protected final R resolveEnclosingElement(P p) {
    Tree leaf = getCurrentPath().getLeaf();
    if (leaf.getKind() == Kind.MEMBER_SELECT) {
      MemberSelectTree memberSelect = (MemberSelectTree) leaf;
      return super.visitMemberSelect(memberSelect, p);
    } else {
      return null;
    }
  }

  @Nullable
  private R resolveElement(TreePath referencingPath, Element referencedElement, P p) {
    return referencedElement.accept(
        new SimpleElementVisitor8<R, P>() {
          @Override
          @Nullable
          public R visitType(TypeElement e, P p) {
            return resolveType(referencingPath, e, p);
          }

          @Override
          @Nullable
          public R visitPackage(PackageElement e, P p) {
            return resolvePackage(referencingPath, e, p);
          }

          @Override
          protected R defaultAction(Element e, P p) {
            throw new IllegalArgumentException(
                String.format("Unexpected element of kind %s: %s", e.getKind(), e));
          }
        },
        p);
  }

  @Nullable
  protected abstract R resolveType(TreePath referencingPath, TypeElement referencedType, P p);

  @Nullable
  protected abstract R resolvePackage(
      TreePath referencingPath, PackageElement referencedPackage, P p);

  @Override
  @Nullable
  public final R scan(Tree tree, P p) {
    switch (tree.getKind()) {
      case ANNOTATED_TYPE:
      case IDENTIFIER:
      case MEMBER_SELECT:
      case PARAMETERIZED_TYPE:
        return super.scan(tree, p);
        // $CASES-OMITTED$
      default:
        throw new IllegalArgumentException(
            String.format("Unexpected tree of kind %s: %s", tree.getKind(), tree));
    }
  }
}
