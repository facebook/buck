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
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.QualifiedNameable;

/**
 * An implementation of {@link Scope} that uses only the information available from a
 * {@link com.sun.source.tree.Tree}. This results in an incomplete implementation; see documentation
 * for individual methods and {@link com.facebook.buck.jvm.java.abi.source} for more information.
 */
abstract class TreeBackedScope implements Scope {
  protected final TreeBackedElements elements;
  protected final TreeBackedTrees trees;
  @Nullable
  protected final TreeBackedScope enclosingScope;
  protected final TreePath path;

  @Nullable
  private TreeBackedPackageElement enclosingPackage;

  protected TreeBackedScope(
      TreeBackedElements elements,
      TreeBackedTrees trees,
      @Nullable TreeBackedScope enclosingScope,
      TreePath path) {
    this.elements = elements;
    this.trees = trees;
    this.enclosingScope = enclosingScope;
    this.path = path;
  }

  @Override
  @Nullable
  public TreeBackedScope getEnclosingScope() {
    return enclosingScope;
  }

  /* package */
  final TreeBackedElement getEnclosingElement() {
    TreeBackedElement result = getEnclosingClass();
    if (result == null) {
      result = getEnclosingPackage();
    }

    return result;
  }

  /* package */ TreeBackedPackageElement getEnclosingPackage() {
    if (enclosingPackage == null) {
      enclosingPackage = Preconditions.checkNotNull(
          elements.getPackageElement(
              TreeBackedTrees.treeToName(path.getCompilationUnit().getPackageName())));
    }
    return enclosingPackage;
  }

  @Override
  @Nullable
  public TreeBackedTypeElement getEnclosingClass() {
    return null;
  }

  @Override
  @Nullable
  public ExecutableElement getEnclosingMethod() {
    // TODO(jkeljo): Maybe implement this for annotation processors
    return null;
  }

  /* package */ Name buildQualifiedName(CharSequence suffix) {
    QualifiedNameable enclosingElement = (QualifiedNameable) getEnclosingElement();
    Name enclosingName = enclosingElement.getQualifiedName();

    if (enclosingName.length() == 0) {
      return elements.getName(suffix);
    }

    return elements.getName(String.format("%s.%s", enclosingName, suffix));
  }
}
