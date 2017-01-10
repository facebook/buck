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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Name;
import javax.lang.model.util.Elements;

/**
 * Resolves declarations found in the ASTs of Java files within a single target to
 * {@link javax.lang.model.element.Element}s, without reference to the dependencies of said target.
 * This effectively does the same thing as the Enter phase of the compiler (see
 * http://openjdk.java.net/groups/compiler/doc/compilation-overview/index.html), but without looking
 * at a target's dependencies. This necessarily requires some assumptions to be made about
 * references to symbols defined in those dependencies. See the documentation of
 * {@link com.facebook.buck.jvm.java.abi.source} for details.
 */
class TreeResolver {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeResolver");
  private final TreeBackedElements elements;
  private final Trees trees;
  private final TreeBackedTypes types;

  public TreeResolver(Trees javacTrees, Elements javacElements) {
    elements = new TreeBackedElements(javacElements);
    trees = new TreeBackedTrees(javacTrees);
    types = new TreeBackedTypes(elements);
  }

  public TreeBackedElements getElements() {
    return elements;
  }

  public Trees getTrees() {
    return trees;
  }

  public TreeBackedTypes getTypes() {
    return types;
  }

  void enterTree(CompilationUnitTree compilationUnit) {
    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("buck.abi.enterTree")) {
      new TreePathScanner<Void, Void>() {
        TreeBackedElement enclosingElement;
        CharSequence scope;

        @Override
        public Void visitCompilationUnit(CompilationUnitTree node, Void aVoid) {
          scope = treeToName(node.getPackageName());

          return super.visitCompilationUnit(node, aVoid);
        }

        @Override
        public Void visitClass(ClassTree node, Void aVoid) {
          Name qualifiedName = node.getSimpleName();
          if (scope.length() > 0) {
            qualifiedName = elements.getName(String.format("%s.%s", scope, qualifiedName));
          }

          TreeBackedTypeElement typeElement =
              new TreeBackedTypeElement(enclosingElement, node, qualifiedName);

          elements.enterTypeElement(typeElement);

          // We have to cast trees here because typing it properly in the field results in test
          // failures due to the class loader hackery required to make tests run.
          ((TreeBackedTrees) trees).enterElement(getCurrentPath(), typeElement);

          CharSequence oldScope = scope;
          TreeBackedElement oldEnclosingElement = enclosingElement;
          scope = typeElement.getQualifiedName();
          enclosingElement = typeElement;
          try {
            return super.visitClass(node, aVoid);
          } finally {
            scope = oldScope;
            enclosingElement = oldEnclosingElement;
          }
        }

        @Override
        public Void visitMethod(MethodTree node, Void aVoid) {
          // TODO(jkeljo): Construct an ExecutableElement

          // The body of a method is not part of the ABI, so don't recurse into them
          return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void aVoid) {
          // TODO(jkeljo): Construct a VariableElement
          // TODO(jkeljo): Evaluate constants

          // Except for constants, we shouldn't look at the next part of a variable decl, because
          // there might be anonymous classes there and those are not part of the ABI
          return null;
        }
      }.scan(compilationUnit, null);
    }
  }

  public void resolve() {
    elements.resolve(types);
  }

  /**
   * Takes a {@link MemberSelectTree} or {@link IdentifierTree} and returns the name it represents
   * as a {@link CharSequence}.
   */
  /* package */
  static CharSequence treeToName(Tree tree) {
    if (tree == null) {
      return "";
    }

    return tree.accept(new SimpleTreeVisitor<CharSequence, Void>() {
      @Override
      protected CharSequence defaultAction(Tree node, Void aVoid) {
        throw new AssertionError(String.format("Unexpected tree of kind: %s", node.getKind()));
      }

      @Override
      public CharSequence visitMemberSelect(MemberSelectTree node, Void aVoid) {
        return String.format(
            "%s.%s",
            node.getExpression().accept(this, aVoid),
            node.getIdentifier());
      }

      @Override
      public CharSequence visitIdentifier(IdentifierTree node, Void aVoid) {
        return node.getName();
      }
    }, null);
  }
}
