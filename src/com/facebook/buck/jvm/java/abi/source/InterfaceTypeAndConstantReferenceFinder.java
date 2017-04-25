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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
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
class InterfaceTypeAndConstantReferenceFinder {
  public interface Listener {
    void onTypeImported(TypeElement type);

    void onTypeReferenceFound(TypeElement type, TreePath path, Element enclosingElement);

    void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element enclosingElement);
  }

  private final Listener listener;
  private final Trees trees;

  public InterfaceTypeAndConstantReferenceFinder(Trees trees, Listener listener) {
    this.trees = trees;
    this.listener = listener;
  }

  public void findReferences(Iterable<? extends CompilationUnitTree> files) {
    files.forEach(file -> findReferencesInSingleFile(file));
  }

  private void findReferencesInSingleFile(CompilationUnitTree file) {
    // Scan the non-private interface portions of the tree, and report any references to types
    // or constants that are found.
    new TreeContextScanner<Void, Void>(trees) {
      /** Reports types that are imported via single-type imports */
      @Override
      public Void visitImport(ImportTree node, Void aVoid) {
        if (node.isStatic()) {
          // Static import; we care only about type imports
          return null;
        }

        MemberSelectTree typeNameTree = (MemberSelectTree) node.getQualifiedIdentifier();
        if (typeNameTree.getIdentifier().contentEquals("*")) {
          // Star import; caller doesn't care
          return null;
        }

        // Single-type import; report to listener
        TreePath importedTypePath = new TreePath(getCurrentPath(), typeNameTree);
        TypeElement importedType =
            (TypeElement) Preconditions.checkNotNull(trees.getElement(importedTypePath));
        listener.onTypeImported(importedType);
        return null;
      }

      /** Restricts the search to non-private classes. */
      @Override
      public Void visitClass(ClassTree node, Void aVoid) {
        Element element = getEnclosingElement();
        // Skip private since they're not part of the interface
        if (isPrivate(element)) {
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

        // Skip the initializers of variables that aren't static constants since they're not part
        // of the interface
        if (element.getConstantValue() == null
            || !element.getModifiers().contains(Modifier.STATIC)) {
          return null;
        }

        scan(node.getInitializer(), aVoid);

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
          TypeElement typeElement = (TypeElement) currentElement;
          if (typeElement.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
            // This is a fully-qualified name
            reportType();
            return null; // Stop; we don't need to report the package reference
          }

          // If it's not a package member, keep going; we want to report the outermost type
          // that is actually named in the source, since that's the thing that might be imported
        } else {
          // If it's not a class reference, it could be a reference to a constant field, either
          // as part of initializing a constant field or as a parameter to an annotation
          VariableElement variableElement = (VariableElement) currentElement;
          if (variableElement.getConstantValue() != null) {
            reportConstant();
            // Keep going; there can also be a type reference that needs reporting
          }
        }

        // Look at the root of this member select; it might be a top-level type
        return super.visitMemberSelect(node, aVoid);
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
          if (variableElement.getConstantValue() != null) {
            reportConstant();
          }
        }

        // Ignore other types of identifiers
        return null;
      }

      private void reportType() {
        TypeMirror currentType = Preconditions.checkNotNull(getCurrentType());
        if (currentType.getKind() != TypeKind.DECLARED) {
          return;
        }

        listener.onTypeReferenceFound(
            (TypeElement) Preconditions.checkNotNull(getCurrentElement()),
            getCurrentPath(),
            getEnclosingElement());
      }

      private void reportConstant() {
        VariableElement variable =
            (VariableElement) Preconditions.checkNotNull(getCurrentElement());
        listener.onConstantReferenceFound(variable, getCurrentPath(), getEnclosingElement());
      }
    }.scan(file, null);
  }

  private static boolean isPrivate(Element element) {
    return element.getModifiers().contains(Modifier.PRIVATE);
  }
}
