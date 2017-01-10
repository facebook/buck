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

import com.facebook.buck.util.exportedfiles.Nullable;
import com.facebook.buck.util.exportedfiles.Preconditions;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.HashMap;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * An implementation of {@link Trees} that uses our tree-backed elements and types when available.
 */
class TreeBackedTrees extends Trees {
  private final Trees javacTrees;
  private final Map<Tree, TreeBackedElement> treeBackedElements = new HashMap<>();
  private final Map<TreeBackedElement, TreePath> elementPaths = new HashMap<>();
  private final Map<Tree, TreeBackedScope> treeBackedScopes = new HashMap<>();

  public TreeBackedTrees(Trees javacTrees) {
    this.javacTrees = javacTrees;
  }

  /* package */ void enterElement(TreePath path, TreeBackedElement element) {
    if (treeBackedElements.containsKey(path.getLeaf())) {
      throw new AssertionError();
    }
    treeBackedElements.put(path.getLeaf(), element);

    if (elementPaths.containsKey(element)) {
      throw new AssertionError();
    }
    elementPaths.put(element, path);
  }

  @Override
  public SourcePositions getSourcePositions() {
    return javacTrees.getSourcePositions();
  }

  @Override
  @Nullable
  public Tree getTree(Element element) {
    TreePath path = getPath(element);
    if (path == null) {
      return null;
    }
    return path.getLeaf();
  }

  @Override
  @Nullable
  public ClassTree getTree(TypeElement element) {
    return (ClassTree) getTree((Element) element);
  }

  @Override
  @Nullable
  public MethodTree getTree(ExecutableElement method) {
    return (MethodTree) getTree((Element) method);
  }

  @Override
  public Tree getTree(Element e, AnnotationMirror a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Tree getTree(Element e, AnnotationMirror a, AnnotationValue v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TreePath getPath(CompilationUnitTree unit, Tree node) {
    return javacTrees.getPath(unit, node);
  }

  @Override
  @Nullable
  public TreePath getPath(Element e) {
    if (e instanceof TreeBackedElement) {
      return Preconditions.checkNotNull(elementPaths.get(e));
    }

    TreePath result = javacTrees.getPath(e);
    if (result != null) {
      // If we've properly hidden all the javac implementations of things, the only way a caller
      // should be able to get a non-`TreeBackedElement` is by looking at classes on the classpath.
      // Those come from .class files, and thus by definition do not have ASTs.

      throw new AssertionError(String.format("Leaked a javac element for: %s", e));
    }

    return null;
  }

  @Override
  public TreePath getPath(Element e, AnnotationMirror a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TreePath getPath(Element e, AnnotationMirror a, AnnotationValue v) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  public TreeBackedElement getElement(TreePath path) {
    return treeBackedElements.get(path.getLeaf());
  }

  @Override
  public TypeMirror getTypeMirror(TreePath path) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  public TreeBackedScope getScope(TreePath path) {
    TreePath walker = path;

    while (walker != null) {
      final Tree leaf = walker.getLeaf();
      switch (leaf.getKind()) {
        case ANNOTATION_TYPE:
        case CLASS:
        case ENUM:
        case INTERFACE:
          if (!treeBackedScopes.containsKey(leaf)) {
            treeBackedScopes.put(
                leaf,
                new TreeBackedClassScope(
                    getScope(walker.getParentPath()),
                    (TreeBackedTypeElement) Preconditions.checkNotNull(getElement(walker))));
          }
          return treeBackedScopes.get(leaf);
        // $CASES-OMITTED$
        default:
          // Skip over other nodes
          break;
      }

      walker = walker.getParentPath();
    }

    return null;
  }

  @Override
  @Nullable
  public String getDocComment(TreePath path) {
    return javacTrees.getDocComment(path);
  }

  @Override
  public boolean isAccessible(Scope scope, TypeElement type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isAccessible(Scope scope, Element member, DeclaredType type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypeMirror getOriginalType(ErrorType errorType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void printMessage(
      Diagnostic.Kind kind, CharSequence msg, Tree t, CompilationUnitTree root) {
    javacTrees.printMessage(kind, msg, t, root);
  }

  @Override
  public TypeMirror getLub(CatchTree tree) {
    throw new UnsupportedOperationException();
  }
}
