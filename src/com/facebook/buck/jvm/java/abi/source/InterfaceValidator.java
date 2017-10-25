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
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * Validates the type and compile-time-constant references in the non-private interface of classes
 * being compiled in the current compilation run, ensuring that they are compatible with correctly
 * generating ABIs from source. When generating ABIs from source, we may be missing some (or most)
 * dependencies.
 *
 * <p>In order to be compatible, type references to missing classes in the non-private interface of
 * a class being compiled must be one of the following:
 *
 * <ul>
 *   <li>The fully-qualified name of the type
 *   <li>The simple name of a type that appears in an import statement
 *   <li>The simple name of a top-level type in the same package as the type being compiled
 * </ul>
 *
 * <p>In order to be compatible, compile-time constants in missing classes may not be referenced
 * from the non-private interface of a class being compiled.
 */
class InterfaceValidator {
  private static final BuckTracing BUCK_TRACING =
      BuckTracing.getInstance("ExpressionTreeResolutionValidator");

  private final Elements elements;
  private final Diagnostic.Kind messageKind;
  private final Trees trees;
  private final SourceOnlyAbiRuleInfo ruleInfo;

  public InterfaceValidator(
      Diagnostic.Kind messageKind, BuckJavacTask task, SourceOnlyAbiRuleInfo ruleInfo) {
    this.messageKind = messageKind;
    trees = task.getTrees();
    elements = task.getElements();
    this.ruleInfo = ruleInfo;
  }

  public void validate(List<? extends CompilationUnitTree> compilationUnits) {
    try (BuckTracing.TraceSection trace = BUCK_TRACING.traceSection("buck.abi.validate")) {
      InterfaceScanner interfaceScanner = new InterfaceScanner(elements, trees);
      for (CompilationUnitTree compilationUnit : compilationUnits) {
        interfaceScanner.findReferences(
            compilationUnit, new ValidatingListener(messageKind, elements, trees, ruleInfo));
      }
    }
  }

  private static PackageElement getPackageElement(Element element) {
    Element walker = element;
    while (walker.getEnclosingElement() != null) {
      walker = walker.getEnclosingElement();
    }

    return (PackageElement) walker;
  }

  private static class ValidatingListener implements InterfaceScanner.Listener {
    private final Set<TypeElement> importedTypes = new HashSet<>();
    private final Set<QualifiedNameable> importedOwners = new HashSet<>();
    private final Diagnostic.Kind messageKind;
    private final Elements elements;
    private final Trees trees;
    private final SourceOnlyAbiRuleInfo ruleInfo;

    public ValidatingListener(
        Diagnostic.Kind messageKind,
        Elements elements,
        Trees trees,
        SourceOnlyAbiRuleInfo ruleInfo) {
      this.messageKind = messageKind;
      this.elements = elements;
      this.trees = trees;
      this.ruleInfo = ruleInfo;
    }

    @Override
    public void onTypeDeclared(TypeElement type, TreePath path) {
      if (type.getKind() == ElementKind.ANNOTATION_TYPE
          && !ruleInfo.ruleIsRequiredForSourceOnlyAbi()) {
        trees.printMessage(
            messageKind,
            String.format(
                "Annotation definitions must be in rules with required_for_source_only_abi = True.\n"
                    + "For a quick fix, add required_for_source_only_abi = True to %s.\n"
                    + "A better fix is to move %s to a new rule that contains only\n"
                    + "annotations, and mark that rule required_for_source_only_abi.\n",
                ruleInfo.getRuleName(), type.getSimpleName()),
            path.getLeaf(),
            path.getCompilationUnit());
      }

      ClassTree classTree = (ClassTree) path.getLeaf();

      // The compiler can handle superclasses and interfaces that are outright missing
      // (because it is possible for those to be generated by annotation processors).
      // However, if a superclass or interface is present, the compiler expects
      // the entire class hierarchy of that class/interface to also be present, and
      // gives a sketchy error if they are missing.
      Tree extendsClause = classTree.getExtendsClause();
      if (extendsClause != null) {
        ensureAbsentOrComplete(type.getSuperclass(), new TreePath(path, extendsClause));
      }

      List<? extends Tree> implementsClause = classTree.getImplementsClause();
      if (implementsClause != null) {
        List<? extends TypeMirror> interfaces = type.getInterfaces();
        for (int i = 0; i < implementsClause.size(); i++) {
          ensureAbsentOrComplete(interfaces.get(i), new TreePath(path, implementsClause.get(i)));
        }
      }
    }

    private void ensureAbsentOrComplete(TypeMirror typeMirror, TreePath path) {
      if (typeMirror.getKind() != TypeKind.DECLARED) {
        return;
      }

      DeclaredType declaredType = (DeclaredType) typeMirror;
      TypeElement typeElement = (TypeElement) declaredType.asElement();

      if (!typeWillBeAvailable(typeElement)) {
        return;
      }

      SortedSet<String> missingDependencies = findMissingDependencies(typeElement);
      if (missingDependencies.isEmpty()) {
        return;
      }

      suggestAddingOrRemovingDependencies(path, missingDependencies);
    }

    private void suggestAddingOrRemovingDependencies(
        TreePath path, SortedSet<String> missingDependencies) {
      trees.printMessage(
          messageKind,
          String.format(
              "Source-only ABI generation requires that this type be unavailable, or that all of its superclasses/interfaces be available.\n"
                  + "To fix, add the following rules to source_only_abi_deps: %s",
              missingDependencies.stream().collect(Collectors.joining(", "))),
          getStartOfTypeExpression(path.getLeaf()),
          path.getCompilationUnit());
    }

    private Tree getStartOfTypeExpression(Tree leaf) {
      Tree startOfTypeExpression = leaf;
      while (startOfTypeExpression.getKind() == Tree.Kind.MEMBER_SELECT) {
        startOfTypeExpression = ((MemberSelectTree) startOfTypeExpression).getExpression();
      }
      return startOfTypeExpression;
    }

    @Override
    public void onImport(
        boolean isStatic,
        boolean isStarImport,
        TreePath leafmostElementPath,
        QualifiedNameable leafmostElement,
        @Nullable Name memberName) {
      if (!isStatic) {
        if (!isStarImport) {
          importedTypes.add((TypeElement) leafmostElement);
        } else {
          importedOwners.add(leafmostElement);
        }
      }
    }

    @Override
    public void onTypeReferenceFound(
        TypeElement canonicalTypeElement, TreePath path, Element referencingElement) {
      if (isCompiledInCurrentRun(canonicalTypeElement)) {
        // Any reference to a type compiled in the current run will be resolved without
        // issue by the compiler.
        return;
      }

      new TypeReferenceScanner(canonicalTypeElement, path, referencingElement).scan();
    }

    @Override
    public void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element referencingElement) {
      TypeElement constantEnclosingType = (TypeElement) constant.getEnclosingElement();

      if (typeWillBeAvailable(constantEnclosingType)) {
        // All good!
        return;
      }

      String owningTarget = ruleInfo.getOwningTarget(elements, constant);
      trees.printMessage(
          messageKind,
          String.format(
              "This constant will not be available during source-only ABI generation.\n"
                  + "For a quick fix, add required_for_source_only_abi = True to %s.\n"
                  + "A better fix is to move %s to a new rule that contains only\n"
                  + "constants, and mark that rule required_for_source_only_abi.\n",
              owningTarget, constant.getEnclosingElement().getSimpleName()),
          path.getLeaf(),
          path.getCompilationUnit());
    }

    private SortedSet<String> findMissingDependencies(TypeElement type) {
      SortedSet<String> result = new TreeSet<>();
      findMissingDependenciesImpl(type, result);
      return result;
    }

    private void findMissingDependenciesImpl(TypeElement type, SortedSet<String> builder) {
      if (!typeWillBeAvailable(type)) {
        builder.add(ruleInfo.getOwningTarget(elements, type));
      }

      findMissingDependenciesImpl(type.getSuperclass(), builder);
      for (TypeMirror interfaceType : type.getInterfaces()) {
        findMissingDependenciesImpl(interfaceType, builder);
      }
    }

    private void findMissingDependenciesImpl(TypeMirror type, SortedSet<String> builder) {
      if (type.getKind() != TypeKind.DECLARED) {
        return;
      }

      DeclaredType declaredType = (DeclaredType) type;
      TypeElement typeElement = (TypeElement) declaredType.asElement();
      findMissingDependenciesImpl(typeElement, builder);
    }

    private boolean typeWillBeAvailable(TypeElement type) {
      return isCompiledInCurrentRun(type)
          || ruleInfo.elementIsAvailableForSourceOnlyAbi(elements, type);
    }

    private boolean isCompiledInCurrentRun(TypeElement typeElement) {
      return trees.getTree(typeElement) != null;
    }

    private class TypeReferenceScanner {
      private final TypeElement canonicalTypeElement;
      private final TreePath referenceTreePath;
      private final Element referencingElement;
      private boolean isCanonicalReference = true;

      public TypeReferenceScanner(
          TypeElement canonicalTypeElement,
          TreePath referenceTreePath,
          Element referencingElement) {
        this.canonicalTypeElement = canonicalTypeElement;
        this.referenceTreePath = referenceTreePath;
        this.referencingElement = referencingElement;
      }

      public void scan() {
        PackageElement referencingPackage = getPackageElement(referencingElement);

        new TreePathScanner<Void, TypeElement>() {
          @Override
          public Void scan(Tree tree, TypeElement canonicalTypeElement) {
            switch (tree.getKind()) {
              case IDENTIFIER:
              case MEMBER_SELECT:
              case PARAMETERIZED_TYPE:
              case ANNOTATED_TYPE:
                return super.scan(tree, canonicalTypeElement);
                // $CASES-OMITTED$
              default:
                throw new IllegalArgumentException(
                    String.format("Unexpected tree of kind %s: %s", tree.getKind(), tree));
            }
          }

          @Override
          public Void visitAnnotatedType(AnnotatedTypeTree node, TypeElement canonicalTypeElement) {
            // Just skip over the annotation; the type reference in it will get its own scanner.
            // The annotation doesn't affect the TypeElement so we can just pass through the same
            // one.
            return super.scan(node.getUnderlyingType(), canonicalTypeElement);
          }

          @Override
          public Void visitParameterizedType(
              ParameterizedTypeTree node, TypeElement canonicalTypeElement) {
            // Skip the type args; they'll each get their own scan. Type args don't affect the
            // element, so we can just pass through the same one.
            return super.scan(node.getType(), canonicalTypeElement);
          }

          @Override
          public Void visitMemberSelect(MemberSelectTree node, TypeElement canonicalTypeElement) {
            TypeElement referencedTypeElement =
                Preconditions.checkNotNull((TypeElement) trees.getElement(getCurrentPath()));
            if (referencedTypeElement.getNestingKind() == NestingKind.TOP_LEVEL) {
              // The rest of the member select must be the package name, so this is a
              // fully-qualified
              // name of a top-level type. We can stop; such names resolve fine.
              return null;
            }

            isCanonicalReference =
                isCanonicalReference && referencedTypeElement == canonicalTypeElement;

            super.scan(
                node.getExpression(), (TypeElement) canonicalTypeElement.getEnclosingElement());

            if (!isCanonicalReference) {
              Name canonicalSimpleName = canonicalTypeElement.getSimpleName();
              Name referencedSimpleName = node.getIdentifier();
              if (canonicalSimpleName != referencedSimpleName) {
                trees.printMessage(
                    messageKind,
                    String.format(
                        "Source-only ABI generation requires that this type be referred to by its canonical name. Use \"%s\" here instead of \"%s\".",
                        canonicalSimpleName, referencedSimpleName),
                    node,
                    getCurrentPath().getCompilationUnit());
              }
            }

            return null;
          }

          @Override
          public Void visitIdentifier(IdentifierTree node, TypeElement canonicalTypeElement) {
            TypeElement referencedTypeElement =
                Preconditions.checkNotNull((TypeElement) trees.getElement(getCurrentPath()));

            isCanonicalReference =
                isCanonicalReference && canonicalTypeElement == referencedTypeElement;
            if (isCanonicalReference && isImported(referencedTypeElement, referencingPackage)) {
              // A canonical reference starting from an imported type will always resolve properly
              // under source-only ABI rules, so nothing to do here.
              return null;
            } else if (isCanonicalReference
                && importedOwners.contains(canonicalTypeElement.getEnclosingElement())) {
              // Star import that's not available at source ABI time
              trees.printMessage(
                  messageKind,
                  String.format(
                      "Source-only ABI generation requires that this type be explicitly imported. Add an import for %s.",
                      canonicalTypeElement.getQualifiedName()),
                  node,
                  referenceTreePath.getCompilationUnit());
            } else {
              suggestQualifiedName(canonicalTypeElement, referencingPackage, getCurrentPath());
            }

            return null;
          }
        }.scan(referenceTreePath, canonicalTypeElement);
      }

      private boolean isImported(
          TypeElement referencedTypeElement, PackageElement enclosingPackage) {
        PackageElement referencedPackage = getPackageElement(referencedTypeElement);
        return isTopLevelTypeInPackage(referencedTypeElement, enclosingPackage)
            || importedTypes.contains(referencedTypeElement)
            || referencedPackage.getQualifiedName().contentEquals("java.lang")
            || (importedOwners.contains(referencedTypeElement.getEnclosingElement())
                && typeWillBeAvailable(referencedTypeElement));
      }

      private boolean isTopLevelTypeInPackage(
          TypeElement referencedTypeElement, PackageElement enclosingPackage) {
        return enclosingPackage == referencedTypeElement.getEnclosingElement();
      }

      private void suggestQualifiedName(
          TypeElement canonicalTypeElement,
          PackageElement referencingPackage,
          TreePath referencingPath) {
        IdentifierTree identifierTree = (IdentifierTree) referencingPath.getLeaf();
        List<QualifiedNameable> enclosingElements = new ArrayList<>();
        QualifiedNameable walker = canonicalTypeElement;
        while (walker != null) {
          enclosingElements.add(walker);
          if (walker.getKind() != ElementKind.PACKAGE) {
            TypeElement walkerType = (TypeElement) walker;
            if (isImported(walkerType, referencingPackage)) {
              break;
            }
          }
          walker = (QualifiedNameable) walker.getEnclosingElement();
        }

        Collections.reverse(enclosingElements);

        String qualifiedName =
            enclosingElements
                .stream()
                .map(
                    element ->
                        element.getKind() == ElementKind.PACKAGE
                            ? element.getQualifiedName()
                            : element.getSimpleName())
                .collect(Collectors.joining("."));

        trees.printMessage(
            messageKind,
            String.format(
                "Source-only ABI generation requires that this type be referred to by its canonical name. Use \"%s\" here instead of \"%s\".",
                qualifiedName, identifierTree.getName()),
            identifierTree,
            referencingPath.getCompilationUnit());
      }
    }
  }
}
