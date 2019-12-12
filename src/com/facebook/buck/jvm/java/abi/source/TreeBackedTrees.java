/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;

/**
 * An implementation of {@link Trees} that uses our tree-backed elements and types when available.
 */
class TreeBackedTrees extends Trees {
  private final Trees javacTrees;
  private final PostEnterCanonicalizer canonicalizer;
  private final Map<Tree, TypeMirror> canonicalTypes = new HashMap<>();

  public static TreeBackedTrees instance(JavaCompiler.CompilationTask task) {
    return ((FrontendOnlyJavacTask) task).getTrees();
  }

  /* package */ TreeBackedTrees(Trees javacTrees, PostEnterCanonicalizer canonicalizer) {
    this.javacTrees = javacTrees;
    this.canonicalizer = canonicalizer;
  }

  /* package */ Trees getJavacTrees() {
    return javacTrees;
  }

  /* package */ void clear() {
    canonicalTypes.clear();
  }

  @Override
  public SourcePositions getSourcePositions() {
    return javacTrees.getSourcePositions();
  }

  @Override
  @Nullable
  public Tree getTree(Element element) {
    if (element.getKind() == ElementKind.TYPE_PARAMETER) {
      // This is a weird javac behavior that we're emulating.
      return null;
    }

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
      return javacTrees.getPath(((TreeBackedElement) e).getUnderlyingElement());
    }
    if (e instanceof InferredElement) {
      // Inferred elements come from dependencies, and you never have trees for those.
      return null;
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
    if (a instanceof TreeBackedAnnotationMirror) {
      return ((TreeBackedAnnotationMirror) a).getTreePath();
    }

    throw new UnsupportedOperationException();
  }

  @Override
  public TreePath getPath(Element e, AnnotationMirror a, AnnotationValue v) {
    if (e instanceof TreeBackedElement) {
      return javacTrees.getPath(e, a, v);
    }

    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  public Element getElement(TreePath path) {
    return canonicalizer.getCanonicalElement(javacTrees.getElement(path));
  }

  @Override
  @Nullable
  public TypeMirror getTypeMirror(TreePath path) {
    Tree leaf = path.getLeaf();
    TypeMirror result = canonicalTypes.get(leaf);
    if (result == null) {
      result = canonicalizer.getCanonicalType(javacTrees.getTypeMirror(path), path);
      canonicalTypes.put(leaf, result);
    }

    return result;
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

  /**
   * Takes a type reference expression and returns the base name of the type it represents (without
   * any type arguments or annotations).
   */
  /* package */
  static CharSequence treeToName(Tree tree) {
    if (tree == null) {
      return "";
    }

    return tree.accept(
        new SimpleTreeVisitor<CharSequence, Void>() {
          @Override
          protected CharSequence defaultAction(Tree node, Void aVoid) {
            throw new AssertionError(String.format("Unexpected tree of kind: %s", node.getKind()));
          }

          @Override
          public CharSequence visitAnnotatedType(AnnotatedTypeTree node, Void aVoid) {
            return node.getUnderlyingType().accept(this, aVoid);
          }

          @Override
          public CharSequence visitParameterizedType(ParameterizedTypeTree node, Void aVoid) {
            return node.getType().accept(this, aVoid);
          }

          @Override
          public CharSequence visitMemberSelect(MemberSelectTree node, Void aVoid) {
            return String.format(
                "%s.%s", node.getExpression().accept(this, aVoid), node.getIdentifier());
          }

          @Override
          public CharSequence visitIdentifier(IdentifierTree node, Void aVoid) {
            return node.getName();
          }
        },
        null);
  }
}
