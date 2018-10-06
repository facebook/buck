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
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * A {@link TreeScanner} that keeps track of the following as it is scanning:
 *
 * <ul>
 *   <li>The {@link TreePath} from the {@link com.sun.source.tree.CompilationUnitTree}
 *   <li>The nearest {@link Element} that encloses the current {@link Tree}
 * </ul>
 */
class TreeContextScanner<R, P> extends TreeScanner<R, P> {

  private final Trees trees;

  @Nullable private TreePath currentPath;
  @Nullable private Element enclosingElement;

  public TreeContextScanner(Trees trees) {
    this.trees = trees;
  }

  protected final TreePath getCurrentPath() {
    return Objects.requireNonNull(currentPath);
  }

  /** Returns the {@link Element} that encloses the current tree path. */
  protected final Element getEnclosingElement() {
    return Objects.requireNonNull(enclosingElement);
  }

  /**
   * Returns the {@link Element} for the type of the current tree. (Equivalent to {@code
   * getCurrentType().asElement()} when the current tree represents a type for which there is an
   * element.)
   */
  @Nullable
  protected final Element getCurrentElement() {
    return trees.getElement(getCurrentPath());
  }

  @Nullable
  protected final TypeMirror getCurrentType() {
    return trees.getTypeMirror(getCurrentPath());
  }

  @Override
  @Nullable
  public R scan(Tree tree, @Nullable P p) {
    if (tree == null) {
      return null;
    }

    TreePath previousPath = currentPath;
    Element previousEnclosingElement = enclosingElement;

    currentPath = new TreePath(currentPath, tree);
    switch (tree.getKind()) {
      case ANNOTATION_TYPE:
      case CLASS:
      case COMPILATION_UNIT:
      case ENUM:
      case INTERFACE:
      case METHOD:
      case VARIABLE:
      case TYPE_PARAMETER:
        enclosingElement = Objects.requireNonNull(trees.getElement(currentPath));
        break;
        // $CASES-OMITTED$
      default:
        break;
    }
    try {
      // This super call will actually visit the tree, now with all the context set up
      return super.scan(tree, p);
    } finally {
      currentPath = previousPath;
      enclosingElement = previousEnclosingElement;
    }
  }
}
