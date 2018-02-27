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
import com.facebook.buck.jvm.java.abi.source.CompletionSimulator.CompletedType;
import com.facebook.buck.jvm.java.abi.source.TreeBackedTypeResolutionSimulator.TreeBackedResolvedType;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
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
  private final Types types;
  private final Trees trees;
  private final SourceOnlyAbiRuleInfo ruleInfo;
  private final FileManagerSimulator fileManager;
  private final CompilerTypeResolutionSimulator compilerResolver;

  public InterfaceValidator(
      Diagnostic.Kind messageKind, BuckJavacTask task, SourceOnlyAbiRuleInfo ruleInfo) {
    this.messageKind = messageKind;
    trees = task.getTrees();
    types = task.getTypes();
    elements = task.getElements();
    this.ruleInfo = ruleInfo;
    fileManager = new FileManagerSimulator(elements, trees, ruleInfo);
    compilerResolver = new CompilerTypeResolutionSimulator(trees, fileManager);
  }

  public void validate(List<? extends CompilationUnitTree> compilationUnits) {
    try (BuckTracing.TraceSection trace = BUCK_TRACING.traceSection("buck.abi.validate")) {
      InterfaceScanner interfaceScanner = new InterfaceScanner(trees);
      for (CompilationUnitTree compilationUnit : compilationUnits) {
        interfaceScanner.findReferences(
            compilationUnit,
            new ValidatingListener(
                messageKind,
                elements,
                types,
                trees,
                ruleInfo,
                fileManager,
                compilerResolver,
                compilationUnit));
      }
    }
  }

  private static class ValidatingListener implements InterfaceScanner.Listener {
    private final Diagnostic.Kind messageKind;
    private final Trees trees;
    private final SourceOnlyAbiRuleInfo ruleInfo;
    private final TreeBackedTypeResolutionSimulator treeBackedResolver;
    private final FileManagerSimulator fileManager;
    private final CompletionSimulator completer;
    private final CompilerTypeResolutionSimulator compilerResolver;
    private final ImportsTracker imports;

    public ValidatingListener(
        Diagnostic.Kind messageKind,
        Elements elements,
        Types types,
        Trees trees,
        SourceOnlyAbiRuleInfo ruleInfo,
        FileManagerSimulator fileManager,
        CompilerTypeResolutionSimulator compilerResolver,
        CompilationUnitTree compilationUnit) {
      this.messageKind = messageKind;
      this.trees = trees;
      this.ruleInfo = ruleInfo;
      this.fileManager = fileManager;
      this.compilerResolver = compilerResolver;
      completer = new CompletionSimulator(fileManager);
      imports =
          new ImportsTracker(
              elements,
              types,
              (PackageElement)
                  Preconditions.checkNotNull(trees.getElement(new TreePath(compilationUnit))));
      treeBackedResolver = new TreeBackedTypeResolutionSimulator(elements, trees, compilationUnit);
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
      CompletedType completedType =
          Preconditions.checkNotNull(completer.complete(typeElement, true));

      switch (completedType.kind) {
        case COMPLETED_TYPE:
        case PARTIALLY_COMPLETED_TYPE:
        case ERROR_TYPE:
          // These are all fine
          break;
        case CRASH:
          reportMissingDeps(completedType, path);
          break;
      }
    }

    public void reportMissingDeps(CompletedType type, TreePath path) {
      // TODO: Different message based on kind.
      trees.printMessage(
          messageKind,
          String.format(
              "Source-only ABI generation requires that this type be unavailable, or that all of its superclasses/interfaces be available.\n"
                  + "To fix, add the following rules to source_only_abi_deps in %s: %s",
              ruleInfo.getRuleName(),
              type.getMissingDependencies().stream().sorted().collect(Collectors.joining(", "))),
          path.getLeaf(),
          path.getCompilationUnit());
    }

    public void reportMissingDeps(ResolvedType type, TreePath path) {
      trees.printMessage(
          messageKind,
          String.format(
              "Source-only ABI generation requires that this type be unavailable, or that all of its superclasses/interfaces be available.\n"
                  + "To fix, add the following rules to source_only_abi_deps in %s: %s",
              ruleInfo.getRuleName(),
              type.missingDependencies.stream().sorted().collect(Collectors.joining(", "))),
          path.getLeaf(),
          path.getCompilationUnit());
    }

    @Override
    public void onImport(
        boolean isStatic,
        boolean isStarImport,
        TreePath leafmostElementPath,
        QualifiedNameable leafmostElement,
        @Nullable Name memberName) {
      if (leafmostElement.getKind() != ElementKind.PACKAGE) {
        if (isStatic) {
          CompletedType completedType = completer.complete(leafmostElement, true);
          if (completedType != null
              && (completedType.kind == CompletedTypeKind.CRASH
                  || completedType.kind == CompletedTypeKind.PARTIALLY_COMPLETED_TYPE)) {
            reportMissingDeps(completedType, leafmostElementPath);
          }
        } else {
          ResolvedType compilerResolvedType = compilerResolver.resolve(leafmostElementPath);
          if (compilerResolvedType != null) {
            switch (compilerResolvedType.kind) {
              case CRASH:
                reportMissingDeps(compilerResolvedType, leafmostElementPath);
                break;
              case RESOLVED_TYPE:
                // Nothing to do; it would resolve fine
                break;
                // $CASES-OMITTED$
              default:
                {
                  TreeBackedResolvedType treeBackedResolvedType =
                      treeBackedResolver.resolve(leafmostElementPath);
                  if (!treeBackedResolvedType.isCorrect()) {
                    if (treeBackedResolvedType.isCorrectable()) {
                      treeBackedResolvedType.reportErrors(messageKind);
                    } else {
                      reportMissingDeps(compilerResolvedType, leafmostElementPath);
                    }
                  }
                }
                break;
            }
          }
        }
      }

      if (!isStatic) {
        if (!isStarImport) {
          imports.importType((TypeElement) leafmostElement, leafmostElementPath);
        } else {
          imports.importMembers(leafmostElement, leafmostElementPath);
        }
      } else if (!isStarImport) {
        imports.importStatic((TypeElement) leafmostElement, Preconditions.checkNotNull(memberName));
      } else {
        imports.importStaticMembers((TypeElement) leafmostElement);
      }
    }

    @Override
    public void onTypeReferenceFound(
        TypeElement canonicalTypeElement, TreePath path, Element referencingElement) {
      compilerResolver.setImports(imports);
      treeBackedResolver.setImports(imports);
      ResolvedType compilerResolvedType = compilerResolver.resolve(path);

      if (compilerResolvedType.kind != ResolvedTypeKind.RESOLVED_TYPE) {
        TreeBackedResolvedType treeBackedResolvedType = treeBackedResolver.resolve(path);
        if (!treeBackedResolvedType.isCorrect()) {
          if (treeBackedResolvedType.isCorrectable()) {
            treeBackedResolvedType.reportErrors(messageKind);
          } else {
            reportMissingDeps(compilerResolvedType, path);
          }
        }
      }
    }

    @Override
    public void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element referencingElement) {
      TypeElement constantEnclosingType = (TypeElement) constant.getEnclosingElement();

      if (fileManager.typeWillBeAvailable(constantEnclosingType)) {
        // All good!
        return;
      }

      String owningTarget = fileManager.getOwningTarget(constant);
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
  }
}
