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
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

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

  public TreeBackedTrees(Trees javacTrees) {
    this.javacTrees = javacTrees;
  }

  @Override
  public SourcePositions getSourcePositions() {
    return javacTrees.getSourcePositions();
  }

  @Override
  public Tree getTree(Element element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ClassTree getTree(TypeElement element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public MethodTree getTree(ExecutableElement method) {
    throw new UnsupportedOperationException();
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
  public TreePath getPath(Element e) {
    throw new UnsupportedOperationException();
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
  public Element getElement(TreePath path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypeMirror getTypeMirror(TreePath path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Scope getScope(TreePath path) {
    throw new UnsupportedOperationException();
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
