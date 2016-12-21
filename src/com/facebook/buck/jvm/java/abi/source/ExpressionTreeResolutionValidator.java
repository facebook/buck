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

import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.util.exportedfiles.Preconditions;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;

/**
 * Because source-based ABI generation can use only the source for which the ABI is being generated,
 * and it has no access to even the source of dependency targets, there are a variety of expressions
 * whose meaning it must guess. This class is used during full compilation to detect instances where
 * it guessed wrong.
 */
class ExpressionTreeResolutionValidator {
  private static final BuckTracing BUCK_TRACING =
      BuckTracing.getInstance("ExpressionTreeResolutionValidator");

  public interface Listener {
    void onIncorrectTypeResolution(
        CompilationUnitTree file,
        ExpressionTree tree,
        DeclaredType guessed,
        DeclaredType actual);
  }

  private final Trees javacTrees;
  private final Elements treesElements;

  public ExpressionTreeResolutionValidator(JavacTask task, TreeResolver treeResolver) {
    javacTrees = Trees.instance(task);
    treesElements = treeResolver.getElements();
  }


  public void validate(CompilationUnitTree compilationUnit, Listener listener) {
    try (BuckTracing.TraceSection trace = BUCK_TRACING.traceSection("buck.abi.validate")) {
      new TreePathScanner<Void, Listener>() {
        @Override
        public Void visitClass(ClassTree node, Listener listener) {
          TypeElement javacElement = (TypeElement) javacTrees.getElement(getCurrentPath());
          Preconditions.checkNotNull(javacElement);

          Name qualifiedName = javacElement.getQualifiedName();
          TypeElement treesElement = treesElements.getTypeElement(qualifiedName);
          if (treesElement == null) {
            throw new AssertionError(
                String.format(
                    "BUG: Should have been able to load trees element for %s", qualifiedName));
          }

          // NOTE: Lots more checks will go here as implementation progresses

          // TODO(jkeljo): inner class support
          return null;
        }
      }.scan(compilationUnit, listener);
    }
  }
}
