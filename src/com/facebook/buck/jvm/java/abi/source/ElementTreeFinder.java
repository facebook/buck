/*
 * Copyright 2018-present Facebook, Inc.
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
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.Element;

/**
 * This class is more complete than {@link Trees#getTree(Element)} in that it will return a tree for
 * method and type parameters. It is also faster for doing a lot of lookups within a given
 * compilation unit.
 */
class ElementTreeFinder {

  public static ElementTreeFinder forCompilationUnit(CompilationUnitTree tree, Trees trees) {
    // Trees.getTree, in addition to being blind to method and type parameters, does an expensive
    // linear-time iteration whenever asked for a tree for a method or variable. We scan the trees
    // once using the much cheaper Trees.getElement and build a map.
    Map<Element, Tree> elementToTreeMap = new HashMap<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(ClassTree node, Void aVoid) {
        elementToTreeMap.put(trees.getElement(getCurrentPath()), node);
        return super.visitClass(node, aVoid);
      }

      @Override
      public Void visitMethod(MethodTree node, Void aVoid) {
        elementToTreeMap.put(trees.getElement(getCurrentPath()), node);
        return super.visitMethod(node, aVoid);
      }

      @Override
      public Void visitVariable(VariableTree node, Void aVoid) {
        elementToTreeMap.put(trees.getElement(getCurrentPath()), node);
        // Don't recurse into variable initializers
        return null;
      }

      @Override
      public Void visitTypeParameter(TypeParameterTree node, Void aVoid) {
        elementToTreeMap.put(trees.getElement(getCurrentPath()), node);
        return super.visitTypeParameter(node, aVoid);
      }

      @Override
      public Void visitBlock(BlockTree node, Void aVoid) {
        // Don't recurse into method bodies
        return null;
      }
    }.scan(tree, null);

    return new ElementTreeFinder(elementToTreeMap);
  }

  private final Map<Element, Tree> elementToTreeMap;

  private ElementTreeFinder(Map<Element, Tree> elementToTreeMap) {
    this.elementToTreeMap = elementToTreeMap;
  }

  @Nullable
  public Tree getTree(Element element) {
    return elementToTreeMap.get(element);
  }
}
