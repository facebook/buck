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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;

/**
 * An implementation of {@link com.sun.source.tree.Scope} for the file scope, using only the
 * information found in a {@link CompilationUnitTree}.
 */
class TreeBackedFileScope extends TreeBackedScope {
  @Nullable
  private List<Element> localElements;

  public TreeBackedFileScope(
      TreeBackedElements elements,
      TreeBackedTrees trees,
      TreePath path) {
    super(
        elements,
        trees,
        null,
        path);
  }

  @Override
  public Iterable<? extends Element> getLocalElements() {
    init();
    return Preconditions.checkNotNull(localElements);
  }

  private void init() {
    if (localElements != null) {
      return;
    }

    List<Element> result = new ArrayList<>();

    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitImport(ImportTree node, Void aVoid) {
        if (node.isStatic()) {
          return null;
        }

        Tree qualifiedIdentifier = node.getQualifiedIdentifier();

        if (qualifiedIdentifier.getKind() == Tree.Kind.MEMBER_SELECT &&
            ((MemberSelectTree) qualifiedIdentifier).getIdentifier().contentEquals("*")) {
          // Star imports not supported yet
          return null;
        }

        result.add(elements.getTypeElement(TreeBackedTrees.treeToName(qualifiedIdentifier)));

        return null;
      }

      @Override
      public Void visitClass(ClassTree node, Void aVoid) {
        result.add(trees.getElement(getCurrentPath()));
        return null;
      }
    }.scan(path.getCompilationUnit(), null);

    localElements = result;
  }
}
