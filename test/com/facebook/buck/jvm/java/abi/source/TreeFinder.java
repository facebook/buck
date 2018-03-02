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

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

public class TreeFinder {
  public static Tree findTreeNamed(CompilationUnitTree compilationUnit, CharSequence name) {
    return new TreeScanner<Tree, Void>() {
      @Override
      public Tree visitClass(ClassTree node, Void aVoid) {
        if (node.getSimpleName().contentEquals(name)) {
          return node;
        }

        return super.visitClass(node, aVoid);
      }

      @Override
      public Tree visitMethod(MethodTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return super.visitMethod(node, aVoid);
      }

      @Override
      public Tree visitVariable(VariableTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return null;
      }

      @Override
      public Tree visitTypeParameter(TypeParameterTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return null;
      }

      @Override
      public Tree visitBlock(BlockTree node, Void aVoid) {
        return null;
      }

      @Override
      public Tree reduce(Tree r1, Tree r2) {
        if (r1 == r2) {
          return r1;
        }
        if (r1 != null && r2 != null) {
          throw new AssertionError();
        } else if (r1 != null) {
          return r1;
        } else {
          return r2;
        }
      }
    }.scan(compilationUnit, null);
  }
}
