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
import com.facebook.buck.util.liteinfersupport.PropagatesNullable;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;

/**
 * After the enter phase is complete, this class can obtain the "canonical" version of any {@link
 * javax.lang.model.element.Element}, {@link TypeMirror}, or {@link AnnotationValue}. The canonical
 * versions of these are the artificial ones if they exist, otherwise the javac one.
 */
class PostEnterCanonicalizer {
  private final TreeBackedElements elements;
  private final TreeBackedTypes types;
  private final Trees javacTrees;
  private final Map<CompilationUnitTree, Map<Name, TreePath>> imports = new HashMap<>();

  public PostEnterCanonicalizer(
      TreeBackedElements elements, TreeBackedTypes types, Trees javacTrees) {
    this.elements = elements;
    this.types = types;
    this.javacTrees = javacTrees;
  }

  public Element getCanonicalElement(@PropagatesNullable Element element) {
    return elements.getCanonicalElement(element);
  }

  public ExecutableElement getCanonicalElement(ExecutableElement element) {
    return Preconditions.checkNotNull(elements.getCanonicalElement(element));
  }

  public List<TypeMirror> getCanonicalTypes(
      List<? extends TypeMirror> types,
      @Nullable TreePath parent,
      @Nullable List<? extends Tree> children) {
    List<TypeMirror> result = new ArrayList<>();
    for (int i = 0; i < types.size(); i++) {
      result.add(
          getCanonicalType(
              types.get(i),
              parent,
              children == null || children.isEmpty() ? null : children.get(i)));
    }
    return result;
  }

  protected TypeMirror getCanonicalType(TreePath classNamePath) {
    return getCanonicalType(getUnderlyingType(classNamePath), classNamePath);
  }

  public TypeMirror getCanonicalType(
      @PropagatesNullable TypeMirror typeMirror, @Nullable TreePath parent, @Nullable Tree child) {
    return getCanonicalType(
        typeMirror, (parent != null && child != null) ? new TreePath(parent, child) : null);
  }

  public TypeMirror getCanonicalType(
      @PropagatesNullable TypeMirror typeMirror, @Nullable TreePath treePath) {
    if (typeMirror == null) {
      return null;
    }

    Tree tree = treePath == null ? null : treePath.getLeaf();
    switch (typeMirror.getKind()) {
      case ARRAY:
        {
          ArrayType arrayType = (ArrayType) typeMirror;
          return types.getArrayType(
              getCanonicalType(
                  arrayType.getComponentType(),
                  treePath,
                  tree == null ? null : ((ArrayTypeTree) tree).getType()));
        }
      case TYPEVAR:
        {
          TypeVariable typeVar = (TypeVariable) typeMirror;
          return elements.getCanonicalElement(typeVar.asElement()).asType();
        }
      case WILDCARD:
        {
          WildcardType wildcardType = (WildcardType) typeMirror;
          Tree boundTree = tree == null ? null : ((WildcardTree) tree).getBound();
          return types.getWildcardType(
              getCanonicalType(wildcardType.getExtendsBound(), treePath, boundTree),
              getCanonicalType(wildcardType.getSuperBound(), treePath, boundTree));
        }
      case DECLARED:
        {
          DeclaredType declaredType = (DeclaredType) typeMirror;

          // It is possible to have a DeclaredType with ErrorTypes for arguments, so we must
          // compute the TreePaths while canonicalizing type arguments
          List<? extends TypeMirror> underlyingTypeArgs = declaredType.getTypeArguments();
          TypeMirror[] canonicalTypeArgs;
          if (underlyingTypeArgs.isEmpty()) {
            canonicalTypeArgs = new TypeMirror[0];
          } else {
            canonicalTypeArgs =
                getCanonicalTypes(
                        underlyingTypeArgs,
                        treePath,
                        tree == null ? null : ((ParameterizedTypeTree) tree).getTypeArguments())
                    .stream()
                    .toArray(TypeMirror[]::new);
          }

          // While it is not possible to have a DeclaredType with an enclosing ErrorType (the only
          // way to get a DeclaredType in the first place is if the compiler can resolve all
          // enclosing types), it *is* possible to have a DeclaredType with an enclosing
          // DeclaredType that has ErrorTypes for type arguments. So we need to check if there's an
          // explicitly specified enclosing type and provide the tree if so.
          TypeMirror enclosingType = declaredType.getEnclosingType();
          DeclaredType canonicalEnclosingType = null;
          if (enclosingType.getKind() != TypeKind.NONE) {
            TreePath enclosingTypePath = treePath;
            if (enclosingTypePath != null
                && enclosingTypePath.getLeaf().getKind() == Tree.Kind.PARAMETERIZED_TYPE) {
              enclosingTypePath =
                  new TreePath(
                      enclosingTypePath,
                      ((ParameterizedTypeTree) enclosingTypePath.getLeaf()).getType());
            }
            if (enclosingTypePath != null
                && enclosingTypePath.getLeaf().getKind() == Tree.Kind.MEMBER_SELECT) {
              enclosingTypePath =
                  new TreePath(
                      enclosingTypePath,
                      ((MemberSelectTree) enclosingTypePath.getLeaf()).getExpression());
            } else {
              enclosingTypePath = null;
            }
            canonicalEnclosingType =
                (DeclaredType) getCanonicalType(enclosingType, enclosingTypePath);
          }

          TypeElement canonicalElement =
              (TypeElement) elements.getCanonicalElement(declaredType.asElement());
          // If an import statement for a type that is not available would shadow a type from a
          // star-imported package (such as java.lang), the compiler will happily resolve to the
          // star-imported type (expecting that the imported type will be filled in by an annotation
          // processor). We check whether we would have inferred something different.
          if (canonicalElement.getNestingKind() == NestingKind.TOP_LEVEL
              && treePath != null
              && treePath.getLeaf().getKind() == Tree.Kind.IDENTIFIER) {
            DeclaredType inferredType = (DeclaredType) getImportedType(treePath);
            if (inferredType != null) {
              TypeElement inferredElement =
                  (TypeElement) elements.getCanonicalElement(inferredType.asElement());
              canonicalElement = inferredElement;
            }
          }

          return types.getDeclaredType(canonicalEnclosingType, canonicalElement, canonicalTypeArgs);
        }
      case PACKAGE:
      case ERROR:
        {
          if (treePath == null) {
            throw new IllegalArgumentException("Cannot resolve error types without a Tree.");
          }

          try {
            return getInferredType(treePath);
          } catch (CompilerErrorException e) {
            javacTrees.printMessage(
                Kind.ERROR, e.getMessage(), treePath.getLeaf(), treePath.getCompilationUnit());
            return typeMirror;
          }
        }
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
        return typeMirror;
      case EXECUTABLE:
      case OTHER:
      case UNION:
      case INTERSECTION:
      default:
        throw new UnsupportedOperationException();
    }
  }

  private TypeMirror getUnderlyingType(TreePath treePath) {
    return Preconditions.checkNotNull(javacTrees.getTypeMirror(treePath));
  }

  private TypeMirror getInferredType(TreePath treePath) throws CompilerErrorException {
    Tree tree = treePath.getLeaf();
    switch (tree.getKind()) {
      case PARAMETERIZED_TYPE:
        {
          ParameterizedTypeTree parameterizedTypeTree = (ParameterizedTypeTree) tree;
          TypeMirror[] typeArguments =
              parameterizedTypeTree
                  .getTypeArguments()
                  .stream()
                  .map(
                      arg -> {
                        TreePath argPath = new TreePath(treePath, arg);
                        return getCanonicalType(argPath);
                      })
                  .toArray(TypeMirror[]::new);

          TreePath baseTypeTreePath = new TreePath(treePath, parameterizedTypeTree.getType());
          DeclaredType baseType = (DeclaredType) getCanonicalType(baseTypeTreePath);

          TypeMirror enclosingType = baseType.getEnclosingType();
          if (enclosingType.getKind() == TypeKind.NONE) {
            enclosingType = null;
          }

          return types.getDeclaredType(
              (DeclaredType) enclosingType, (TypeElement) baseType.asElement(), typeArguments);
        }
      case UNBOUNDED_WILDCARD:
        return types.getWildcardType(null, null);
      case SUPER_WILDCARD:
        {
          WildcardTree wildcardTree = (WildcardTree) tree;
          TreePath boundTreePath = new TreePath(treePath, wildcardTree.getBound());
          return types.getWildcardType(null, getCanonicalType(boundTreePath));
        }
      case EXTENDS_WILDCARD:
        {
          WildcardTree wildcardTree = (WildcardTree) tree;
          TreePath boundTreePath = new TreePath(treePath, wildcardTree.getBound());
          return types.getWildcardType(getCanonicalType(boundTreePath), null);
        }
      case MEMBER_SELECT:
        {
          MemberSelectTree memberSelectTree = (MemberSelectTree) tree;
          TypeMirror canonicalBaseType =
              getCanonicalType(new TreePath(treePath, memberSelectTree.getExpression()));
          if (!(canonicalBaseType instanceof StandaloneTypeMirror)) {
            javacTrees.printMessage(
                Kind.ERROR,
                String.format(
                    "cannot find symbol\nsymbol: class %s\nlocation: class %s",
                    memberSelectTree.getIdentifier(), canonicalBaseType),
                treePath.getLeaf(),
                treePath.getCompilationUnit());
            return canonicalBaseType; // This isn't right, but it doesn't matter what we return
            // since we're failing anyway
          }
          StandaloneTypeMirror baseType = (StandaloneTypeMirror) canonicalBaseType;
          ArtificialQualifiedNameable baseElement;
          Name identifier = memberSelectTree.getIdentifier();
          if (baseType.getKind() == TypeKind.PACKAGE) {
            baseElement = ((StandalonePackageType) baseType).asElement();
            if (isProbablyPackageName(identifier)) {
              return types.getPackageType(
                  elements.getOrCreatePackageElement((PackageElement) baseElement, identifier));
            }
          } else {
            baseElement = (ArtificialQualifiedNameable) ((DeclaredType) baseType).asElement();
          }

          DeclaredType enclosingType = null;
          if (baseType.getKind() == TypeKind.DECLARED
              && !(baseType instanceof InferredDeclaredType)) {
            DeclaredType baseDeclaredType = (DeclaredType) baseType;
            if (!baseDeclaredType.getTypeArguments().isEmpty()
                || baseDeclaredType.getEnclosingType().getKind() != TypeKind.NONE) {
              enclosingType = baseDeclaredType;
            }
          }

          ArtificialTypeElement typeElement =
              elements.getOrCreateTypeElement(baseElement, identifier);
          return types.getDeclaredType(enclosingType, typeElement);
        }
      case IDENTIFIER:
        {
          // If it's imported, then it must be a class; look it up
          TypeMirror importedType = getImportedType(treePath);
          if (importedType != null) return importedType;

          // Infer the type by heuristic
          IdentifierTree identifierTree = (IdentifierTree) tree;
          Name identifier = identifierTree.getName();
          if (isProbablyPackageName(identifier)) {
            return types.getPackageType(elements.getOrCreatePackageElement(null, identifier));
          }
          ArtificialPackageElement packageElement =
              (ArtificialPackageElement)
                  elements.getCanonicalElement(
                      Preconditions.checkNotNull(
                          javacTrees.getElement(new TreePath(treePath.getCompilationUnit()))));
          return types.getDeclaredType(elements.getOrCreateTypeElement(packageElement, identifier));
        }
        // $CASES-OMITTED$
      default:
        throw new AssertionError(String.format("Unexpected tree kind %s", tree.getKind()));
    }
  }

  @Nullable
  private TypeMirror getImportedType(TreePath treePath) {
    TreePath importedIdentifierPath = getImportedIdentifier(treePath);
    if (importedIdentifierPath != null) {
      return getCanonicalType(importedIdentifierPath);
    }

    return null;
  }

  private boolean isProbablyPackageName(CharSequence identifier) {
    return Character.isLowerCase(identifier.charAt(0));
  }

  /**
   * Canonicalizes any {@link javax.lang.model.element.Element}s, {@link TypeMirror}s, or {@link
   * AnnotationValue}s found in the given object, which is expected to have been obtained by calling
   * {@link AnnotationValue#getValue()}.
   */
  /* package */ Object getCanonicalValue(AnnotationValue annotationValue, TreePath valueTreePath) {
    return annotationValue.accept(
        new SimpleAnnotationValueVisitor8<Object, Void>() {
          @Override
          public Object visitType(TypeMirror t, Void aVoid) {
            return getCanonicalType(t, valueTreePath);
          }

          @Override
          public Object visitEnumConstant(VariableElement c, Void aVoid) {
            return Preconditions.checkNotNull(elements.getCanonicalElement(c));
          }

          @Override
          public Object visitAnnotation(AnnotationMirror a, Void aVoid) {
            return new TreeBackedAnnotationMirror(a, valueTreePath, PostEnterCanonicalizer.this);
          }

          @Override
          public Object visitArray(List<? extends AnnotationValue> values, Void aVoid) {
            Tree valueTree = valueTreePath.getLeaf();
            if (valueTree instanceof NewArrayTree) {
              NewArrayTree tree = (NewArrayTree) valueTree;
              List<? extends ExpressionTree> valueTrees = tree.getInitializers();

              List<TreeBackedAnnotationValue> result = new ArrayList<>();
              for (int i = 0; i < values.size(); i++) {
                result.add(
                    new TreeBackedAnnotationValue(
                        values.get(i),
                        new TreePath(valueTreePath, valueTrees.get(i)),
                        PostEnterCanonicalizer.this));
              }
              return result;
            } else {
              return Collections.singletonList(
                  new TreeBackedAnnotationValue(
                      values.get(0),
                      new TreePath(valueTreePath, valueTree),
                      PostEnterCanonicalizer.this));
            }
          }

          @Override
          public Object visitString(String s, Void aVoid) {
            if (getUnderlyingType(valueTreePath).getKind() == TypeKind.ERROR) {
              Tree leaf = valueTreePath.getLeaf();
              if (leaf instanceof MemberSelectTree
                  && ((MemberSelectTree) leaf).getIdentifier().contentEquals("class")) {
                TreePath classNamePath =
                    new TreePath(valueTreePath, ((MemberSelectTree) leaf).getExpression());

                return getCanonicalType(classNamePath);
              } else {
                javacTrees.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Could not resolve constant. Either inline the value or add required_for_source_only_abi = True to the build rule that contains it.",
                    leaf,
                    valueTreePath.getCompilationUnit());
              }
            }

            return super.visitString(s, aVoid);
          }

          @Override
          protected Object defaultAction(Object o, Void aVoid) {
            // Everything else (primitives, Strings, enums) doesn't need canonicalization
            return o;
          }
        },
        null);
  }

  @Nullable
  private TreePath getImportedIdentifier(TreePath identifierTreePath) {
    IdentifierTree identifierTree = (IdentifierTree) identifierTreePath.getLeaf();

    Map<Name, TreePath> imports =
        this.imports.computeIfAbsent(
            identifierTreePath.getCompilationUnit(),
            compilationUnitTree -> {
              Map<Name, TreePath> result = new HashMap<>();
              TreePath rootPath = new TreePath(compilationUnitTree);
              for (ImportTree importTree : compilationUnitTree.getImports()) {
                if (importTree.isStatic()) {
                  continue;
                }

                MemberSelectTree importedIdentifierTree =
                    (MemberSelectTree) importTree.getQualifiedIdentifier();
                if (importedIdentifierTree.getIdentifier().contentEquals("*")) {
                  continue;
                }

                TreePath importTreePath = new TreePath(rootPath, importTree);
                TreePath importedIdentifierTreePath =
                    new TreePath(importTreePath, importedIdentifierTree);
                result.put(importedIdentifierTree.getIdentifier(), importedIdentifierTreePath);
              }
              return result;
            });

    return imports.get(identifierTree.getName());
  }
}
