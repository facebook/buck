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
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementScanner8;
import javax.tools.JavaFileObject;

/**
 * Creates {@link TreeBackedElement}s for each element in the {@link CompilationUnitTree}s known to
 * the compiler. This is analogous to the "Enter" phase of javac.
 */
class TreeBackedEnter {
  private static final BuckTracing BUCK_TRACING = BuckTracing.getInstance("TreeBackedEnter");
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;
  private final Trees javacTrees;
  private final EnteringElementScanner elementScanner = new EnteringElementScanner();
  private final PostEnterCanonicalizer canonicalizer;

  TreeBackedEnter(TreeBackedElements elements, TreeBackedTypes types, Trees javacTrees) {
    this.elements = elements;
    this.types = types;
    this.javacTrees = javacTrees;
    canonicalizer = new PostEnterCanonicalizer(elements, types);
  }

  public void enter(CompilationUnitTree compilationUnit) {
    try (BuckTracing.TraceSection t = BUCK_TRACING.traceSection("buck.enter")) {
      elementScanner.enter(compilationUnit);
    }
  }

  // TODO(jkeljo): Consider continuing to build TreePath objects as we go, so that we don't have to
  // re-query the tree when creating the elements in `TreeBackedElements`.
  private class EnteringElementScanner extends ElementScanner8<Void, Void> {
    private final Deque<TreeBackedElement> contextStack = new ArrayDeque<>();

    private TreeBackedElement getCurrentContext() {
      return contextStack.peek();
    }

    public void enter(CompilationUnitTree compilationUnitTree) {
      if (compilationUnitTree.getTypeDecls().isEmpty()
          && compilationUnitTree.getPackageAnnotations().isEmpty()) {
        // Nothing interesting, so don't even enter the package element
        return;
      }

      TreePath rootPath = new TreePath(compilationUnitTree);
      TreeBackedPackageElement packageElement = enterPackageElement(rootPath);
      try (ElementContext c = new ElementContext(packageElement)) {
        List<? extends Tree> typeDecls = compilationUnitTree.getTypeDecls();
        enterTypes(rootPath, typeDecls);
      }
    }

    private TreeBackedPackageElement enterPackageElement(TreePath rootPath) {
      CompilationUnitTree compilationUnitTree = rootPath.getCompilationUnit();
      PackageElement packageElement =
          (PackageElement) Preconditions.checkNotNull(javacTrees.getElement(rootPath));
      TreeBackedPackageElement treeBackedPackageElement =
          elements.enterElement(packageElement, this::newTreeBackedPackage);
      if (compilationUnitTree
          .getSourceFile()
          .isNameCompatible("package-info", JavaFileObject.Kind.SOURCE)) {
        treeBackedPackageElement.setTree(compilationUnitTree);
        enterAnnotationMirrors(
            treeBackedPackageElement, compilationUnitTree.getPackageAnnotations());
      }
      return treeBackedPackageElement;
    }

    private void enterTypes(TreePath rootPath, List<? extends Tree> typeDecls) {
      for (Tree tree : typeDecls) {
        // We use the Tree to find the top-level type elements in a given compilation unit,
        // then switch to Element scanning so that we can catch elements created by the compiler
        // that don't have a tree, such as default constructors or the generated methods on enums.
        TreePath typePath = new TreePath(rootPath, tree);
        Element element = javacTrees.getElement(typePath);
        if (element != null) {
          scan(element);
        } else if (tree.getKind() != Tree.Kind.EMPTY_STATEMENT) {
          throw new AssertionError(String.format("Unexpected tree kind %s", tree.getKind()));
        }
      }
    }

    @Override
    public Void visitType(TypeElement e, Void v) {
      TreeBackedTypeElement newClass = elements.enterElement(e, this::newTreeBackedType);
      try (ElementContext c = new ElementContext(newClass)) {
        super.visitType(e, v);
        super.scan(e.getTypeParameters(), v);
        return null;
      }
    }

    @Override
    public Void visitTypeParameter(TypeParameterElement e, Void v) {
      TreeBackedTypeParameterElement typeParameter =
          elements.enterElement(e, this::newTreeBackedTypeParameter);

      try (ElementContext c = new ElementContext(typeParameter)) {
        return super.visitTypeParameter(e, v);
      }
    }

    @Override
    public Void visitExecutable(ExecutableElement e, Void v) {
      TreeBackedExecutableElement method = elements.enterElement(e, this::newTreeBackedExecutable);

      try (ElementContext c = new ElementContext(method)) {
        super.visitExecutable(e, v);
        super.scan(e.getTypeParameters(), v);
        return null;
      }
    }

    @Override
    public Void visitVariable(VariableElement e, Void v) {
      elements.enterElement(e, this::newTreeBackedVariable);
      return super.visitVariable(e, v);
    }

    private TreeBackedPackageElement newTreeBackedPackage(PackageElement underlyingPackage) {
      return new TreeBackedPackageElement(underlyingPackage, canonicalizer);
    }

    private TreeBackedTypeElement newTreeBackedType(TypeElement underlyingType) {
      ClassTree tree = Preconditions.checkNotNull(javacTrees.getTree(underlyingType));
      TreeBackedTypeElement typeElement =
          new TreeBackedTypeElement(
              types, underlyingType, getCurrentContext(), tree, canonicalizer);
      enterAnnotationMirrors(
          typeElement,
          tree == null ? Collections.emptyList() : tree.getModifiers().getAnnotations());
      return typeElement;
    }

    private TreeBackedTypeParameterElement newTreeBackedTypeParameter(
        TypeParameterElement underlyingTypeParameter) {
      TreeBackedParameterizable enclosingElement = (TreeBackedParameterizable) getCurrentContext();

      // Trees.getTree does not work for TypeParameterElements, so we must find it ourselves
      TypeParameterTree tree =
          findTypeParameterTree(enclosingElement, underlyingTypeParameter.getSimpleName());
      TreeBackedTypeParameterElement result =
          new TreeBackedTypeParameterElement(
              types, underlyingTypeParameter, tree, enclosingElement, canonicalizer);
      enterAnnotationMirrors(
          result, tree == null ? Collections.emptyList() : tree.getAnnotations());

      enclosingElement.addTypeParameter(result);
      return result;
    }

    private TreeBackedExecutableElement newTreeBackedExecutable(
        ExecutableElement underlyingExecutable) {
      MethodTree tree = javacTrees.getTree(underlyingExecutable);
      TreeBackedExecutableElement result =
          new TreeBackedExecutableElement(
              underlyingExecutable, getCurrentContext(), tree, canonicalizer);
      enterAnnotationMirrors(
          result, tree == null ? Collections.emptyList() : tree.getModifiers().getAnnotations());
      return result;
    }

    private TreeBackedVariableElement newTreeBackedVariable(VariableElement underlyingVariable) {
      TreeBackedElement enclosingElement = getCurrentContext();
      VariableTree tree = reallyGetTreeForVariable(enclosingElement, underlyingVariable);
      TreeBackedVariableElement result =
          new TreeBackedVariableElement(underlyingVariable, enclosingElement, tree, canonicalizer);
      enterAnnotationMirrors(
          result, tree == null ? Collections.emptyList() : tree.getModifiers().getAnnotations());
      return result;
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

  private void enterAnnotationMirrors(
      TreeBackedElement element, List<? extends AnnotationTree> annotationTrees) {
    List<? extends AnnotationMirror> underlyingAnnotations =
        element.getUnderlyingElement().getAnnotationMirrors();
    if (underlyingAnnotations.isEmpty()) {
      return;
    }
    if (underlyingAnnotations.size() != annotationTrees.size()) {
      throw new IllegalArgumentException();
    }

    for (int i = 0; i < underlyingAnnotations.size(); i++) {
      element.addAnnotationMirror(
          new TreeBackedAnnotationMirror(
              underlyingAnnotations.get(i), annotationTrees.get(i), canonicalizer));
    }
  }

  private TypeParameterTree findTypeParameterTree(
      TreeBackedParameterizable element, Name simpleName) {
    List<? extends TypeParameterTree> typeParameters = getTypeParameters(element);
    for (TypeParameterTree typeParameter : typeParameters) {
      if (typeParameter.getName().equals(simpleName)) {
        return typeParameter;
      }
    }
    throw new AssertionError();
  }

  private List<? extends TypeParameterTree> getTypeParameters(TreeBackedParameterizable element) {
    if (element instanceof TreeBackedTypeElement) {
      TreeBackedTypeElement typeElement = (TreeBackedTypeElement) element;
      return typeElement.getTree().getTypeParameters();
    }

    TreeBackedExecutableElement executableElement = (TreeBackedExecutableElement) element;
    // TreeBackedExecutables with a null tree occur only for compiler-generated methods such
    // as default construvtors. Those never have type parameters, so we should never find
    // ourselves here without a tree.
    return Preconditions.checkNotNull(executableElement.getTree()).getTypeParameters();
  }

  /**
   * {@link Trees#getTree(Element)} cannot get a tree for a method parameter. This method is a
   * workaround.
   */
  @Nullable
  private VariableTree reallyGetTreeForVariable(
      TreeBackedElement enclosing, VariableElement parameter) {
    if (enclosing instanceof TreeBackedTypeElement) {
      return (VariableTree) javacTrees.getTree(parameter);
    }

    TreeBackedExecutableElement method = (TreeBackedExecutableElement) enclosing;
    MethodTree methodTree = method.getTree();
    if (methodTree == null) {
      return null;
    }

    for (VariableTree variableTree : methodTree.getParameters()) {
      if (variableTree.getName().equals(parameter.getSimpleName())) {
        return variableTree;
      }
    }
    throw new AssertionError();
  }
}
