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

import com.facebook.buck.util.liteinfersupport.Preconditions;

class TypeResolverFactory {
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;
  private final TreeBackedTrees trees;

  public TypeResolverFactory(
      TreeBackedElements elements,
      TreeBackedTypes types,
      TreeBackedTrees trees) {
    this.elements = elements;
    this.types = types;
    this.trees = trees;
  }

  public TypeResolver newInstance(TreeBackedElement enclosingElement) {
    return new TypeResolver(
        elements,
        types,
        trees.getScope(Preconditions.checkNotNull(trees.getPath(enclosingElement))));
  }
}
