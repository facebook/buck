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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementScanner8;

/**
 * Creates {@link TreeBackedElement}s for each element in the {@link CompilationUnitTree}s known to
 * the compiler. This is analogous to the "Enter" phase of javac.
 */
class TreeBackedEnter {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeBackedEnter");
  private final TreeBackedElements elements;
  private final Trees javacTrees;
  private final EnteringTreePathScanner treeScanner = new EnteringTreePathScanner();
  private final EnteringElementScanner elementScanner = new EnteringElementScanner();

  TreeBackedEnter(TreeBackedElements elements, Trees javacTrees) {
    this.elements = elements;
    this.javacTrees = javacTrees;
  }

  public void enter(CompilationUnitTree compilationUnit) {
    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("buck.enter")) {
      treeScanner.scan(compilationUnit, null);
    }
  }

  // TODO(jkeljo): Consider continuing to build TreePath objects as we go, so that we don't have to
  // re-query the tree when creating the elements in `TreeBackedElements`.
  private class EnteringTreePathScanner extends TreePathScanner<Void, Void> {
    @Override
    public Void visitClass(ClassTree node, Void v) {
      // We use a TreePathScanner to find the top-level type elements in a given compilation unit,
      // then switch to Element scanning so that we can catch elements created by the compiler
      // that don't have a tree, such as default constructors or the generated methods on enums.
      return elementScanner.scan(
          Preconditions.checkNotNull(javacTrees.getElement(getCurrentPath())));
    }
  }

  private class EnteringElementScanner extends ElementScanner8<Void, Void> {
    private final Deque<TreeBackedElement> contextStack = new ArrayDeque<>();

    private TreeBackedElement getCurrentContext() {
      return contextStack.peek();
    }

    @Override
    public Void visitType(TypeElement e, Void v) {
      TreeBackedTypeElement newClass = (TreeBackedTypeElement) elements.enterElement(e);
      try (ElementContext c = new ElementContext(newClass)) {
        super.visitType(e, v);
        super.scan(e.getTypeParameters(), v);
        return null;
      }
    }

    @Override
    public Void visitTypeParameter(TypeParameterElement e, Void v) {
      TreeBackedTypeParameterElement typeParameter =
          (TreeBackedTypeParameterElement) elements.enterElement(e);

      TreeBackedParameterizable currentParameterizable =
          (TreeBackedParameterizable) getCurrentContext();
      currentParameterizable.addTypeParameter(typeParameter);

      try (ElementContext c = new ElementContext(typeParameter)) {
        return super.visitTypeParameter(e, v);
      }
    }

    @Override
    public Void visitExecutable(ExecutableElement e, Void v) {
      TreeBackedExecutableElement method = (TreeBackedExecutableElement) elements.enterElement(e);

      try (ElementContext c = new ElementContext(method)) {
        super.visitExecutable(e, v);
        super.scan(e.getTypeParameters(), v);
        return null;
      }
    }

    @Override
    public Void visitVariable(VariableElement e, Void v) {
      elements.enterElement(e);
      return super.visitVariable(e, v);
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
