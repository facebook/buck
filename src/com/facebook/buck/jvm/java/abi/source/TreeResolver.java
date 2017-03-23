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
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/**
 * Resolves {@link TreePath}s to {@link TypeMirror}s, using {@link StandaloneTypeMirror}s when
 * required and javac's {@link TypeMirror}s otherwise.
 */
class TreeResolver {
  private final TypeResolvingVisitor typeResolvingVisitor = new TypeResolvingVisitor();
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;
  private final Trees javacTrees;
  private final Map<Tree, TypeMirror> treesToTypes = new HashMap<>();

  public TreeResolver(TreeBackedElements elements, Trees javacTrees, TreeBackedTypes types) {
    this.elements = elements;
    this.javacTrees = javacTrees;
    this.types = types;
  }

  /* package */ void clear() {
    treesToTypes.clear();
  }

  @Nullable
  public TypeMirror getType(TreePath path) {
    Tree tree = path.getLeaf();
    TypeMirror result = treesToTypes.get(tree);
    if (result != null) {
      return result;
    }

    treesToTypes.put(tree, resolveType(path));

    return treesToTypes.get(tree);
  }

  private TypeMirror resolveType(TreePath path) {
    TypeMirror javacType = Preconditions.checkNotNull(javacTrees.getTypeMirror(path));
    return needsStandaloneTypeMirror(javacType)
        ? path.getLeaf().accept(typeResolvingVisitor, path)
        : javacType;
  }

  /**
   * Types need a standalone type mirror if any part of them corresponds to a
   * {@link TreeBackedElement}.
   */
  private boolean needsStandaloneTypeMirror(@Nullable TypeMirror typeMirror) {
    if (typeMirror == null) {
      return false;
    }

    switch (typeMirror.getKind()) {
      case ARRAY: {
        ArrayType arrayType = (ArrayType) typeMirror;
        return needsStandaloneTypeMirror(arrayType.getComponentType());
      }
      case TYPEVAR: {
        TypeVariable typeVar = (TypeVariable) typeMirror;
        TypeParameterElement element = (TypeParameterElement) typeVar.asElement();

        return hasTree(element) || anyNeedsStandaloneTypeMirror(element.getBounds());
      }
      case WILDCARD: {
        WildcardType wildcardType = (WildcardType) typeMirror;
        return needsStandaloneTypeMirror(wildcardType.getExtendsBound()) ||
            needsStandaloneTypeMirror(wildcardType.getSuperBound());
      }
      case PACKAGE:
        return true;
      case DECLARED: {
        DeclaredType declaredType = (DeclaredType) typeMirror;

        return hasTree(declaredType.asElement()) ||
            anyNeedsStandaloneTypeMirror(declaredType.getTypeArguments());
      }
      case ERROR:
        throw new UnsupportedOperationException("ErrorType NYI");
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case FLOAT:
      case DOUBLE:
      case VOID:
      case NONE:
      case NULL:
        return false;
      case EXECUTABLE:
      case OTHER:
      case UNION:
      case INTERSECTION:
      default:
        throw new UnsupportedOperationException();
    }
  }

  private boolean anyNeedsStandaloneTypeMirror(Iterable<? extends TypeMirror> typeMirrors) {
    for (TypeMirror typeMirror : typeMirrors) {
      if (needsStandaloneTypeMirror(typeMirror)) {
        return true;
      }
    }

    return false;
  }

  private boolean hasTree(Element element) {
    return elements.getCanonicalElement(element) instanceof TreeBackedElement;
  }

  private class TypeResolvingVisitor extends SimpleTreeVisitor<TypeMirror, TreePath> {
    @Override
    protected TypeMirror defaultAction(Tree node, TreePath trees) {
      throw new UnsupportedOperationException(String.format(
          "Type resolution NYI for trees of kind %s",
          node.getKind()));
    }

    @Override
    public TypeMirror visitArrayType(ArrayTypeTree node, TreePath path) {
      return types.getArrayType(Preconditions.checkNotNull(getType(path, node.getType())));
    }

    @Override
    public TypeMirror visitWildcard(WildcardTree node, TreePath path) {
      TypeMirror bound = Preconditions.checkNotNull(getType(path, node.getBound()));
      switch (node.getKind()) {
        case EXTENDS_WILDCARD:
          return types.getWildcardType(bound, null);
        case SUPER_WILDCARD:
          return types.getWildcardType(null, bound);
        // $CASES-OMITTED$
        default:
          throw new AssertionError();
      }
    }

    @Override
    public TypeMirror visitPrimitiveType(PrimitiveTypeTree node, TreePath path) {
      return Preconditions.checkNotNull(getJavacType(path));
    }

    @Override
    public TypeMirror visitParameterizedType(ParameterizedTypeTree node, TreePath path) {
      DeclaredType rawType = (DeclaredType) Preconditions.checkNotNull(getType(
          path,
          node.getType()));
      List<TypeMirror> typeArguments = getTypes(path, node.getTypeArguments());

      if (rawType.getKind() == TypeKind.DECLARED) {
        // The compiler was able to resolve the raw type, but not the type args.
        return new StandaloneDeclaredType(
            (TypeElement) rawType.asElement(),
            typeArguments,
            rawType.getEnclosingType(),
            rawType.getAnnotationMirrors());
      }

      throw new UnsupportedOperationException("ErrorType not yet supported");
    }

    @Override
    public TypeMirror visitMemberSelect(MemberSelectTree node, TreePath path) {
      TypeMirror javacType = Preconditions.checkNotNull(getJavacType(path));
      if (javacType.getKind() == TypeKind.PACKAGE) {
        return getCanonicalElementType(path);
      }

      if (javacType.getKind() == TypeKind.DECLARED) {
        DeclaredType enclosingType = null;

        TypeMirror containing = Preconditions.checkNotNull(getType(path, node.getExpression()));
        if (containing.getKind() == TypeKind.DECLARED) {
          DeclaredType containingDeclaredType = (DeclaredType) containing;
          if (!containingDeclaredType.getTypeArguments().isEmpty()) {
            enclosingType = containingDeclaredType;
          }
        }

        TypeElement canonicalTypeElement = (TypeElement) getCanonicalElement(path);
        return types.getDeclaredType(enclosingType, canonicalTypeElement);
      }

      throw new UnsupportedOperationException("ErrorType NYI");
    }

    @Override
    public TypeMirror visitIdentifier(IdentifierTree node, TreePath path) {
      TypeMirror javacType = Preconditions.checkNotNull(getJavacType(path));
      switch (javacType.getKind()) {
        case PACKAGE:
          return getCanonicalElementType(path);
        case ERROR:
          throw new UnsupportedOperationException("ErrorType NYI");
          // $CASES-OMITTED$
        default:
          return getCanonicalElementType(path);
      }
    }

    @Override
    public TypeMirror visitClass(ClassTree node, TreePath path) {
      return getCanonicalElementType(path);
    }

    @Override
    public TypeMirror visitMethod(MethodTree node, TreePath path) {
      return getCanonicalElementType(path);
    }

    @Override
    public TypeMirror visitVariable(VariableTree node, TreePath path) {
      return getCanonicalElementType(path);
    }

    @Override
    public TypeMirror visitTypeParameter(TypeParameterTree node, TreePath path) {
      return getCanonicalElementType(path);
    }

    TypeMirror getCanonicalElementType(TreePath path) {
      Element canonicalElement = getCanonicalElement(path);
      return canonicalElement.asType();
    }

    private Element getCanonicalElement(TreePath path) {
      return Preconditions.checkNotNull(elements.getCanonicalElement(javacTrees.getElement(path)));
    }

    @Nullable
    private TypeMirror getJavacType(TreePath path) {
      return javacTrees.getTypeMirror(path);
    }

    private List<TypeMirror> getTypes(TreePath parent, List<? extends Tree> children) {
      return children.stream().map(
          child -> Preconditions.checkNotNull(getType(parent, child)))
          .collect(Collectors.toList());
    }

    @Nullable
    private TypeMirror getType(TreePath parent, Tree tree) {
      return TreeResolver.this.getType(new TreePath(parent, tree));
    }
  }
}
