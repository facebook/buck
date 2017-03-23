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

import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.util.ArrayDeque;
import java.util.Deque;

import javax.lang.model.element.Element;

/**
 * Creates {@link TreeBackedElement}s for each element in the {@link CompilationUnitTree}s known
 * to the compiler. This is analogous to the "Enter" phase of javac.
 */
class TreeBackedEnter {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeBackedEnter");
  private final TreeBackedElements elements;
  private final Trees javacTrees;
  private final ElementEnteringScanner scanner = new ElementEnteringScanner();

  TreeBackedEnter(TreeBackedElements elements, Trees javacTrees) {
    this.elements = elements;
    this.javacTrees = javacTrees;
  }

  public void enter(CompilationUnitTree compilationUnit) {
    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("enter")) {
      scanner.scan(compilationUnit, null);
    }
  }

  private class ElementEnteringScanner extends TreePathScanner<Void, Void> {
    private final Deque<TreeBackedElement> contextStack = new ArrayDeque<>();

    private TreeBackedElement getCurrentContext() {
      return contextStack.peek();
    }

    private Element getCurrentJavacElement() {
      return Preconditions.checkNotNull(javacTrees.getElement(getCurrentPath()));
    }

    @Override
    public Void visitClass(ClassTree node, Void v) {
      TreeBackedTypeElement newClass =
          (TreeBackedTypeElement) elements.enterElement(getCurrentJavacElement());
      try (ElementContext c = new ElementContext(newClass)) {
        return super.visitClass(node, v);
      }
    }

    @Override
    public Void visitTypeParameter(TypeParameterTree node, Void v) {
      TreeBackedTypeParameterElement typeParameter =
          (TreeBackedTypeParameterElement) elements.enterElement(getCurrentJavacElement());

      TreeBackedParameterizable currentParameterizable =
          (TreeBackedParameterizable) getCurrentContext();
      currentParameterizable.addTypeParameter(typeParameter);

      try (ElementContext c = new ElementContext(typeParameter)) {
        return super.visitTypeParameter(node, v);
      }
    }

    @Override
    public Void visitMethod(MethodTree node, Void v) {
      TreeBackedExecutableElement method =
          (TreeBackedExecutableElement) elements.enterElement(getCurrentJavacElement());

      try (ElementContext c = new ElementContext(method)) {
        scan(node.getParameters(), v);
        return null;
      }
    }

    @Override
    public Void visitVariable(VariableTree node, Void v) {
      elements.enterElement(getCurrentJavacElement());

      return null;
    }

    @Override
    public Void visitBlock(BlockTree node, Void v) {
      // Don't recurse into method bodies
      return null;
    }

    private class ElementContext implements AutoCloseable {
      public ElementContext(TreeBackedElement newContext) {
        contextStack.push(newContext);
      }


      @Override
      public void close() {
        contextStack.pop();
      }
    }
  }
}
