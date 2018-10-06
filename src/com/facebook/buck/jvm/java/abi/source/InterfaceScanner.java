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
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Examines the non-private interfaces of types defined in one or more {@link CompilationUnitTree}s
 * and finds references to types or compile-time constants. This is intended for use during full
 * compilation, to expose references that may be problematic when generating ABIs without
 * dependencies.
 */
class InterfaceScanner {
  public interface Listener {
    void onTypeDeclared(TypeElement type, TreePath path);

    /**
     * An import statement was encountered.
     *
     * @param isStatic true for static imports
     * @param isStarImport true for star imports
     * @param leafmostElementPath the path of the leafmost known element in the imported type
     *     expression
     * @param leafmostElement the leafmost known element in the imported type expression. For
     *     single-type imports, this is the imported type. For the rest, this is the type or package
     *     enclosing the imported element(s).
     * @param memberName for named static imports, the name of the static members to import.
     *     Otherwise null.
     */
    void onImport(
        boolean isStatic,
        boolean isStarImport,
        TreePath leafmostElementPath,
        QualifiedNameable leafmostElement,
        @Nullable Name memberName);

    void onTypeReferenceFound(TypeElement type, TreePath path, Element referencingElement);

    void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element referencingElement);
  }

  private final Trees trees;

  public InterfaceScanner(Trees trees) {
    this.trees = trees;
  }

  public void findReferences(CompilationUnitTree file, Listener listener) {
    // Scan the non-private interface portions of the tree, and report any references to types
    // or constants that are found.
    new TreeContextScanner<Void, Void>(trees) {
      private boolean inInitializer = false;

      @Override
      public Void visitCompilationUnit(CompilationUnitTree node, Void aVoid) {
        // We change the order relative to our superclass so that the imports get scanned first
        // (in case they are needed to resolve the package annotations), and we don't bother
        // scanning the package name because it is not needed.
        scan(node.getImports(), aVoid);
        scan(node.getPackageAnnotations(), aVoid);

        // getTypeDecls has a bug whereby it can return some imports in addition to types, but
        // since getImports returns all of the imports in that case we can still iterate this last
        // like the superclass does.
        scan(node.getTypeDecls(), aVoid);
        return null;
      }

      /** Reports types that are imported via single-type imports */
      @Override
      public Void visitImport(ImportTree importTree, Void aVoid) {
        TreePath importTreePath = getCurrentPath();
        MemberSelectTree importedExpression =
            (MemberSelectTree) importTree.getQualifiedIdentifier();
        TreePath importedExpressionPath = new TreePath(importTreePath, importedExpression);
        Name simpleName = importedExpression.getIdentifier();

        boolean isStatic = importTree.isStatic();
        boolean isStarImport = simpleName.contentEquals("*");

        TreePath leafmostElementPath = importedExpressionPath;
        if (isStarImport || isStatic) {
          leafmostElementPath =
              new TreePath(importedExpressionPath, importedExpression.getExpression());
        }
        QualifiedNameable leafmostElement =
            (QualifiedNameable) Objects.requireNonNull(trees.getElement(leafmostElementPath));

        listener.onImport(isStatic, isStarImport, leafmostElementPath, leafmostElement, simpleName);

        return null;
      }

      /** Restricts the search to non-private classes. */
      @Override
      public Void visitClass(ClassTree node, Void aVoid) {
        Element element = getEnclosingElement();

        listener.onTypeDeclared((TypeElement) element, getCurrentPath());

        // Skip private since they're not part of the interface
        if (!element.getKind().isClass() && isPrivate(element)) {
          return null;
        }

        return super.visitClass(node, aVoid);
      }

      /** Restricts the search to non-private methods */
      @Override
      public Void visitMethod(MethodTree node, Void aVoid) {
        Element element = getEnclosingElement();
        // Skip private since they're not part of the interface
        if (isPrivate(element)) {
          return null;
        }

        return super.visitMethod(node, aVoid);
      }

      /**
       * Restricts the search to non-private variables, and only searches the initializer if the
       * variable is a compile-time constant field.
       */
      @Override
      public Void visitVariable(VariableTree node, Void aVoid) {
        VariableElement element = (VariableElement) getEnclosingElement();
        // Skip private since they're not part of the interface
        if (isPrivate(element)) {
          return null;
        }

        scan(node.getModifiers(), aVoid);
        scan(node.getType(), aVoid);

        // Skip the initializers of variables that aren't compile-time constants since they're not
        // part of the interface
        if (element.getConstantValue() == null) {
          return null;
        }

        inInitializer = true;
        try {
          scan(node.getInitializer(), aVoid);
        } finally {
          inInitializer = false;
        }

        return null;
      }

      /** Prevents the search from descending into method bodies. */
      @Override
      public Void visitBlock(BlockTree node, Void aVoid) {
        // We care only about the interface, so we don't need to recurse into code blocks
        return null;
      }

      /**
       * Reports references to types or constants via fully or partially-qualified names, wherever
       * they might appear in the interface.
       */
      @Override
      public Void visitMemberSelect(MemberSelectTree node, Void aVoid) {
        Element currentElement = getCurrentElement();
        if (currentElement == null) {
          // This can happen with the package name tree at compilation unit level
          return null;
        }

        // The other visit methods have ensured that we're only seeing MemberSelectTrees that are
        // part of the non-private interface, so let's figure out if this one represents a type
        // reference or constant reference that we need to report
        ElementKind kind = currentElement.getKind();
        if (kind.isClass() || kind.isInterface()) {
          reportType();
        } else {
          // If it's not a class reference, it could be a reference to a constant field, either
          // as part of initializing a constant field or as a parameter to an annotation
          VariableElement variableElement = (VariableElement) currentElement;
          if (variableElement.getConstantValue() != null) {
            reportConstant();
          } else {
            if (variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
              // Not technically a compile-time constant, but still needs to be present for
              // stuff to resolve properly.
              reportConstant();
            }

            // Keep looking for the type reference.
            return super.visitMemberSelect(node, aVoid);
          }
        }

        return null;
      }

      /**
       * Reports references to types or constants via simple name, wherever it might appear in the
       * interface
       */
      @Override
      public Void visitIdentifier(IdentifierTree node, Void aVoid) {
        Element currentElement = getCurrentElement();
        if (currentElement == null) {
          // This can happen with the package name tree at compilation unit level
          return null;
        }

        ElementKind kind = currentElement.getKind();
        if (kind.isClass() || kind.isInterface()) {
          // Identifier represents a type (either imported, from the same package, or a member of
          // the current class or a supertype/interface); report it
          reportType();
        } else if (kind == ElementKind.FIELD) {
          // Identifier is a field name. Check if it's a constant and report if so.
          VariableElement variableElement = (VariableElement) currentElement;
          if (variableElement.getConstantValue() != null
              || variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
            reportConstant();
          }
        }

        // Ignore other types of identifiers
        return null;
      }

      private void reportType() {
        if (inInitializer) {
          return;
        }

        TypeMirror currentType = Objects.requireNonNull(getCurrentType());
        if (currentType.getKind() != TypeKind.DECLARED) {
          return;
        }

        listener.onTypeReferenceFound(
            (TypeElement) Objects.requireNonNull(getCurrentElement()),
            getCurrentPath(),
            getEnclosingElement());
      }

      private void reportConstant() {
        VariableElement variable = (VariableElement) Objects.requireNonNull(getCurrentElement());
        listener.onConstantReferenceFound(variable, getCurrentPath(), getEnclosingElement());
      }
    }.scan(file, null);
  }

  private static boolean isPrivate(Element element) {
    return element.getModifiers().contains(Modifier.PRIVATE);
  }
}
