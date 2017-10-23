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
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
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
      new InterfaceScanner(
              elements, trees, new ValidatingListener(messageKind, elements, trees, ruleInfo))
          .findReferences(compilationUnits);
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
    public void onTypeImported(TypeElement type) {
      importedTypes.add(type);
    }

    @Override
    public void onMembersImported(QualifiedNameable typeOrPackage) {
      importedOwners.add(typeOrPackage);
    }

    @Override
    public void onTypeReferenceFound(
        TypeElement canonicalTypeElement, TreePath path, Element referencingElement) {
      if (isCompiledInCurrentRun(canonicalTypeElement)
          || isFullyQualifiedOrImportedCanonicalName(
              canonicalTypeElement, path, referencingElement)) {
        // All good!
        return;
      }

      PackageElement enclosingPackage = getPackageElement(referencingElement);
      String minimalQualifiedName =
          findMinimalQualifiedName(path, enclosingPackage, canonicalTypeElement);

      // TODO(jkeljo): Clearer message
      trees.printMessage(
          messageKind,
          String.format("Must qualify the name: %s", minimalQualifiedName),
          path.getLeaf(),
          path.getCompilationUnit());
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

    private boolean isImported(TypeElement referencedTypeElement, PackageElement enclosingPackage) {
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

    private boolean isFullyQualifiedOrImportedCanonicalName(
        TypeElement canonicalTypeElement, TreePath path, Element referencingElement) {
      return path.getLeaf()
          .accept(
              new SimpleTreeVisitor<Boolean, Void>() {
                @Override
                public Boolean visitIdentifier(IdentifierTree node, Void aVoid) {
                  // Single-type imports must be canonical by definition.
                  return isImported(canonicalTypeElement, getPackageElement(referencingElement));
                }

                @Override
                public Boolean visitMemberSelect(MemberSelectTree node, Void aVoid) {
                  TypeElement referencedTypeElement =
                      Preconditions.checkNotNull((TypeElement) trees.getElement(path));
                  if (referencedTypeElement.getNestingKind() == NestingKind.TOP_LEVEL) {
                    // The rest of the member select must be the package name, making this
                    // a fully-qualified and canonical name.
                    return true;
                  }

                  return canonicalTypeElement == referencedTypeElement
                      && isFullyQualifiedOrImportedCanonicalName(
                          (TypeElement) canonicalTypeElement.getEnclosingElement(),
                          new TreePath(path, node.getExpression()),
                          referencingElement);
                }

                @Override
                public Boolean visitParameterizedType(ParameterizedTypeTree node, Void aVoid) {
                  return isFullyQualifiedOrImportedCanonicalName(
                      canonicalTypeElement, new TreePath(path, node.getType()), referencingElement);
                }

                @Override
                public Boolean visitAnnotatedType(AnnotatedTypeTree node, Void aVoid) {
                  return isFullyQualifiedOrImportedCanonicalName(
                      canonicalTypeElement,
                      new TreePath(path, node.getUnderlyingType()),
                      referencingElement);
                }

                @Override
                protected Boolean defaultAction(Tree node, Void aVoid) {
                  throw new IllegalArgumentException(
                      String.format("Unexpected tree of kind %s: %s", node.getKind(), node));
                }
              },
              null);
    }

    private String findMinimalQualifiedName(
        TreePath path, PackageElement enclosingPackage, TypeElement typeElement) {
      List<QualifiedNameable> enclosingElements = new ArrayList<>();
      QualifiedNameable walker = typeElement;

      while (walker.getKind() != ElementKind.PACKAGE
          && !isFullyQualifiedOrImportedCanonicalName(
              (TypeElement) walker, path, enclosingPackage)) {
        enclosingElements.add(walker);
        walker = (QualifiedNameable) walker.getEnclosingElement();
      }
      enclosingElements.add(walker);

      StringBuilder resultBuilder = new StringBuilder();
      for (int i = enclosingElements.size() - 1; i >= 0; i--) {
        QualifiedNameable element = enclosingElements.get(i);
        if (element.getKind() == ElementKind.PACKAGE) {
          resultBuilder.append(element.getQualifiedName());
        } else {
          resultBuilder.append(element.getSimpleName());
        }

        if (i > 0) {
          resultBuilder.append(".");
        }
      }

      return resultBuilder.toString();
    }
  }
}
