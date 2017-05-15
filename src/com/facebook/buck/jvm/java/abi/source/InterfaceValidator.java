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
import com.facebook.buck.jvm.java.abi.source.api.BootClasspathOracle;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
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
  private final BootClasspathOracle bootClasspathOracle;

  public InterfaceValidator(
      Diagnostic.Kind messageKind, BuckJavacTask task, BootClasspathOracle bootClasspathOracle) {
    this.messageKind = messageKind;
    trees = task.getTrees();
    elements = task.getElements();
    this.bootClasspathOracle = bootClasspathOracle;
  }

  public void validate(List<? extends CompilationUnitTree> compilationUnits) {
    try (BuckTracing.TraceSection trace = BUCK_TRACING.traceSection("buck.abi.validate")) {
      new InterfaceTypeAndConstantReferenceFinder(
              trees,
              new InterfaceTypeAndConstantReferenceFinder.Listener() {
                private final Set<Element> importedTypes = new HashSet<>();

                @Override
                public void onTypeImported(TypeElement type) {
                  importedTypes.add(type);
                }

                @Override
                public void onTypeReferenceFound(
                    TypeElement referencedType, TreePath path, Element enclosingElement) {
                  PackageElement enclosingPackage = getPackageElement(enclosingElement);

                  if (typeWillBeAvailable(referencedType)
                      || referenceIsLegalForMissingTypes(path, enclosingPackage, referencedType)) {
                    // All good!
                    return;
                  }

                  String minimalQualifiedName =
                      findMinimalQualifiedName(path, enclosingPackage, referencedType);

                  // TODO(jkeljo): Clearer message
                  trees.printMessage(
                      messageKind,
                      String.format("Must qualify the name: %s", minimalQualifiedName),
                      path.getLeaf(),
                      path.getCompilationUnit());
                }

                @Override
                public void onConstantReferenceFound(
                    VariableElement constant, TreePath path, Element enclosingElement) {
                  TypeElement constantEnclosingType = (TypeElement) constant.getEnclosingElement();

                  if (typeWillBeAvailable(constantEnclosingType)) {
                    // All good!
                    return;
                  }

                  // TODO(jkeljo): Clearer message
                  trees.printMessage(
                      messageKind,
                      String.format(
                          "Must inline the constant value: %s", constant.getConstantValue()),
                      path.getLeaf(),
                      path.getCompilationUnit());
                }

                private boolean typeWillBeAvailable(TypeElement type) {
                  return isCompiledInCurrentRun(type) || isOnBootClasspath(type);
                }

                private boolean isCompiledInCurrentRun(TypeElement typeElement) {
                  return trees.getTree(typeElement) != null;
                }

                private boolean isOnBootClasspath(TypeElement typeElement) {
                  return bootClasspathOracle.isOnBootClasspath(
                      elements.getBinaryName(typeElement).toString());
                }

                private boolean referenceIsLegalForMissingTypes(
                    TreePath path,
                    PackageElement enclosingPackage,
                    TypeElement referencedTypeElement) {
                  return isImported(referencedTypeElement)
                      || isTopLevelTypeInPackage(referencedTypeElement, enclosingPackage)
                      || isFullyQualified(path, referencedTypeElement);
                }

                private boolean isImported(TypeElement referencedTypeElement) {
                  return importedTypes.contains(referencedTypeElement);
                }

                private boolean isTopLevelTypeInPackage(
                    TypeElement referencedTypeElement, PackageElement enclosingPackage) {
                  return enclosingPackage == referencedTypeElement.getEnclosingElement();
                }

                private boolean isFullyQualified(TreePath path, TypeElement referencedTypeElement) {
                  return referencedTypeElement
                      .getQualifiedName()
                      .contentEquals(TreeBackedTrees.treeToName(path.getLeaf()));
                }

                private String findMinimalQualifiedName(
                    TreePath path, PackageElement enclosingPackage, TypeElement typeElement) {
                  List<QualifiedNameable> enclosingElements = new ArrayList<>();
                  QualifiedNameable walker = typeElement;

                  while (walker.getKind() != ElementKind.PACKAGE
                      && !referenceIsLegalForMissingTypes(
                          path, enclosingPackage, (TypeElement) walker)) {
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
              })
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
}
