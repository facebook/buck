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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;

/**
 * Creates {@link TreeBackedElement}s for each element in the {@link CompilationUnitTree}s known
 * to the compiler. This is analogous to the "Enter" phase of javac.
 */
class TreeBackedEnter {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeBackedEnter");
  private final TreeBackedElements elements;
  private final Trees javacTrees;

  TreeBackedEnter(TreeBackedElements elements, Trees javacTrees) {
    this.elements = elements;
    this.javacTrees = javacTrees;
  }

  /**
   * Returns the top-level types discovered while entering the given trees into the symbol table.
   */
  public List<TreeBackedTypeElement> enter(
      Iterable<? extends CompilationUnitTree> compilationUnits) {
    List<TreeBackedTypeElement> topLevelTypes = new ArrayList<>();

    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("enter")) {
      for (CompilationUnitTree compilationUnit : compilationUnits) {
        enter(compilationUnit, topLevelTypes);
      }

      return Collections.unmodifiableList(topLevelTypes);
    }
  }

  private void enter(
      CompilationUnitTree compilationUnit,
      List<TreeBackedTypeElement> topLevelTypes) {
    new ElementEnteringScanner(topLevelTypes).scan(compilationUnit, null);
  }

  private class ElementEnteringScanner extends TreePathScanner<Void, Void> {
    private final Deque<TreeBackedElement> contextStack = new ArrayDeque<>();
    private final List<TreeBackedTypeElement> topLevelTypes;

    private ElementEnteringScanner(List<TreeBackedTypeElement> topLevelTypes) {
      this.topLevelTypes = topLevelTypes;
    }

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
        if (newClass.getNestingKind() == NestingKind.TOP_LEVEL) {
          topLevelTypes.add(newClass);
        }

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
      // TODO(jkeljo): Construct an ExecutableElement

      // The body of a method is not part of the ABI, so don't recurse into them
      return null;
    }

    @Override
    public Void visitVariable(VariableTree node, Void v) {
      // TODO(jkeljo): Construct a VariableElement
      // TODO(jkeljo): Evaluate constants

      // Except for constants, we shouldn't look at the next part of a variable decl, because
      // there might be anonymous classes there and those are not part of the ABI
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
