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
import com.sun.source.util.TreePath;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * An implementation of {@link com.sun.source.tree.Scope} for the class scope, using only the
 * information found in a {@link TypeElement}
 */
class TreeBackedClassScope extends TreeBackedScope {
  @Nullable
  private TreeBackedTypeElement enclosingClass;

  public TreeBackedClassScope(
      TreeBackedElements elements,
      TreeBackedTrees trees,
      TreeBackedScope enclosingScope,
      TreePath path) {
    super(elements, trees, enclosingScope, path);
  }

  @Override
  public Iterable<? extends Element> getLocalElements() {
    return Preconditions.checkNotNull(getEnclosingClass()).getTypeParameters();
  }

  @Override
  public TreeBackedScope getEnclosingScope() {
    return Preconditions.checkNotNull(super.getEnclosingScope());
  }

  @Override
  TreeBackedPackageElement getEnclosingPackage() {
    return getEnclosingScope().getEnclosingPackage();
  }

  @Override
  public TreeBackedTypeElement getEnclosingClass() {
    if (enclosingClass == null) {
      enclosingClass = (TreeBackedTypeElement) Preconditions.checkNotNull(trees.getElement(path));
    }

    return enclosingClass;
  }
}
