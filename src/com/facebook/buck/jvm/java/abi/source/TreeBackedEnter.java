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
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
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

  private class EnteringElementScanner extends ElementScanner8<Void, Void> {
    private final Deque<TreeBackedElement> contextStack = new ArrayDeque<>();
    @Nullable private TreePath currentPath;
    @Nullable private Tree currentTree;

    private TreeBackedElement getCurrentContext() {
      return contextStack.peek();
    }

    private TreePath getCurrentPath() {
      return Preconditions.checkNotNull(currentPath);
    }

    public void enter(CompilationUnitTree compilationUnitTree) {
      if (compilationUnitTree.getTypeDecls().isEmpty()
          && compilationUnitTree.getPackageAnnotations().isEmpty()) {
        // Nothing interesting, so don't even enter the package element
        return;
      }

      currentPath = new TreePath(compilationUnitTree);
      currentTree = compilationUnitTree;
      try {
        TreeBackedPackageElement packageElement = enterPackageElement();
        try (ElementContext c = new ElementContext(packageElement)) {
          enterTypes();
        }
      } finally {
        currentPath = null;
        currentTree = null;
      }
    }

    private TreeBackedPackageElement enterPackageElement() {
      CompilationUnitTree compilationUnitTree = getCurrentPath().getCompilationUnit();
      PackageElement packageElement =
          (PackageElement) Preconditions.checkNotNull(javacTrees.getElement(getCurrentPath()));
      TreeBackedPackageElement treeBackedPackageElement =
          elements.enterElement(packageElement, this::newTreeBackedPackage);
      if (compilationUnitTree
          .getSourceFile()
          .isNameCompatible("package-info", JavaFileObject.Kind.SOURCE)) {
        treeBackedPackageElement.setTreePath(getCurrentPath());
        enterAnnotationMirrors(treeBackedPackageElement);
      }
      return treeBackedPackageElement;
    }

    private void enterTypes() {
      for (Tree tree : getCurrentPath().getCompilationUnit().getTypeDecls()) {
        // We use the Tree to find the top-level type elements in a given compilation unit,
        // then switch to Element scanning so that we can catch elements created by the compiler
        // that don't have a tree, such as default constructors or the generated methods on enums.
        TreePath previousPath = currentPath;
        Tree previousTree = currentTree;
        currentPath = new TreePath(currentPath, tree);
        currentTree = tree;
        try {
          Element element = javacTrees.getElement(currentPath);
          if (element != null) {
            scan(element);
          } else if (tree.getKind() != Tree.Kind.EMPTY_STATEMENT) {
            throw new AssertionError(String.format("Unexpected tree kind %s", tree.getKind()));
          }
        } finally {
          currentPath = previousPath;
          currentTree = previousTree;
        }
      }
    }

    @Override
    public Void scan(Element e, Void aVoid) {
      TreePath previousPath = currentPath;
      Tree previousTree = currentTree;
      currentTree = reallyGetTree(currentPath, e);
      currentPath = currentTree == null ? null : new TreePath(currentPath, currentTree);
      try {
        if (currentPath != null && javacTrees.getElement(currentPath) != e) {
          throw new AssertionError(
              String.format(
                  "Element mismatch!\n  Expected: %s\n  Found: %s\n",
                  e, javacTrees.getElement(currentPath)));
        }
        return super.scan(e, aVoid);
      } finally {
        currentPath = previousPath;
        currentTree = previousTree;
      }
    }

    /**
     * Trees.getTree has a couple of blind spots -- method and type parameters. This method works
     * around those.
     */
    @Nullable
    private Tree reallyGetTree(@Nullable TreePath parentPath, Element element) {
      if (parentPath == null) {
        return null;
      }

      Tree result = javacTrees.getTree(element);
      if (result == null) {
        Tree parentTree = parentPath.getLeaf();
        Name simpleName = element.getSimpleName();
        if (element.getKind() == ElementKind.PARAMETER) {
          MethodTree methodTree = (MethodTree) parentTree;
          for (VariableTree variableTree : methodTree.getParameters()) {
            if (variableTree.getName().equals(simpleName)) {
              return variableTree;
            }
          }
        } else if (element.getKind() == ElementKind.TYPE_PARAMETER) {
          for (TypeParameterTree typeParameter : getTypeParameters(parentTree)) {
            if (typeParameter.getName().equals(simpleName)) {
              return typeParameter;
            }
          }
        } else {
          // If it's not a param or a type param, it's compiler generated
          return null;
        }

        throw new AssertionError(String.format("Couldn't find tree for element %s", element));
      }

      return result;
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
      TreeBackedTypeElement typeElement =
          new TreeBackedTypeElement(
              types, underlyingType, getCurrentContext(), getCurrentPath(), canonicalizer);
      enterAnnotationMirrors(typeElement);
      return typeElement;
    }

    private TreeBackedTypeParameterElement newTreeBackedTypeParameter(
        TypeParameterElement underlyingTypeParameter) {
      TreeBackedParameterizable enclosingElement = (TreeBackedParameterizable) getCurrentContext();

      // TreeBackedExecutables with a null tree occur only for compiler-generated methods such
      // as default construvtors. Those never have type parameters, so we should never find
      // ourselves here without a tree.
      TreeBackedTypeParameterElement result =
          new TreeBackedTypeParameterElement(
              types, underlyingTypeParameter, getCurrentPath(), enclosingElement, canonicalizer);
      enterAnnotationMirrors(result);

      enclosingElement.addTypeParameter(result);
      return result;
    }

    private TreeBackedExecutableElement newTreeBackedExecutable(
        ExecutableElement underlyingExecutable) {
      TreeBackedExecutableElement result =
          new TreeBackedExecutableElement(
              underlyingExecutable, getCurrentContext(), currentPath, canonicalizer);
      enterAnnotationMirrors(result);
      return result;
    }

    private TreeBackedVariableElement newTreeBackedVariable(VariableElement underlyingVariable) {
      TreeBackedElement enclosingElement = getCurrentContext();
      TreeBackedVariableElement result =
          new TreeBackedVariableElement(
              underlyingVariable, enclosingElement, currentPath, canonicalizer);
      enterAnnotationMirrors(result);
      return result;
    }

    private void enterAnnotationMirrors(TreeBackedElement element) {
      List<? extends AnnotationMirror> underlyingAnnotations =
          element.getUnderlyingElement().getAnnotationMirrors();
      if (underlyingAnnotations.isEmpty()) {
        return;
      }

      List<? extends AnnotationTree> annotationTrees = getAnnotationTrees(currentTree);
      if (underlyingAnnotations.size() != annotationTrees.size()) {
        throw new IllegalArgumentException();
      }

      for (int i = 0; i < underlyingAnnotations.size(); i++) {
        element.addAnnotationMirror(
            new TreeBackedAnnotationMirror(
                underlyingAnnotations.get(i),
                new TreePath(currentPath, annotationTrees.get(i)),
                canonicalizer));
      }
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

  private static List<? extends AnnotationTree> getAnnotationTrees(@Nullable Tree parentTree) {
    if (parentTree == null) {
      return Collections.emptyList();
    }

    return parentTree.accept(
        new SimpleTreeVisitor<List<? extends AnnotationTree>, Void>() {
          @Override
          public List<? extends AnnotationTree> visitCompilationUnit(
              CompilationUnitTree node, Void aVoid) {
            return node.getPackageAnnotations();
          }

          @Override
          public List<? extends AnnotationTree> visitClass(ClassTree node, Void aVoid) {
            return node.getModifiers().getAnnotations();
          }

          @Override
          public List<? extends AnnotationTree> visitMethod(MethodTree node, Void aVoid) {
            return node.getModifiers().getAnnotations();
          }

          @Override
          public List<? extends AnnotationTree> visitVariable(VariableTree node, Void aVoid) {
            return node.getModifiers().getAnnotations();
          }

          @Override
          public List<? extends AnnotationTree> visitTypeParameter(
              TypeParameterTree node, Void aVoid) {
            return node.getAnnotations();
          }

          @Override
          protected List<? extends AnnotationTree> defaultAction(Tree node, Void aVoid) {
            throw new AssertionError(String.format("Unexpected tree: %s", node));
          }
        },
        null);
  }

  private static List<? extends TypeParameterTree> getTypeParameters(Tree parentTree) {
    return parentTree.accept(
        new SimpleTreeVisitor<List<? extends TypeParameterTree>, Void>() {
          @Override
          public List<? extends TypeParameterTree> visitClass(ClassTree node, Void aVoid) {
            return node.getTypeParameters();
          }

          @Override
          public List<? extends TypeParameterTree> visitMethod(MethodTree node, Void aVoid) {
            return node.getTypeParameters();
          }

          @Override
          protected List<? extends TypeParameterTree> defaultAction(Tree node, Void aVoid) {
            throw new AssertionError(String.format("Unexpected tree %s", node));
          }
        },
        null);
  }
}
