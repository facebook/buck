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
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.tools.Diagnostic;

/** Simulates the type resolution performed by {@link TreeBackedEnter} for source-only ABIs. */
class TreeBackedTypeResolutionSimulator {

  private final Elements elements;
  private final Trees trees;
  private final PackageElement enclosingPackage;
  @Nullable private ImportsTracker imports = null;

  public TreeBackedTypeResolutionSimulator(
      Elements elements, Trees trees, CompilationUnitTree compilationUnit) {
    this.elements = elements;
    this.trees = trees;
    enclosingPackage =
        (PackageElement) Objects.requireNonNull(trees.getElement(new TreePath(compilationUnit)));
  }

  public void setImports(ImportsTracker imports) {
    this.imports = imports;
  }

  private boolean isTopLevelInReferencingPackage(TypeElement type) {
    return type.getEnclosingElement() == enclosingPackage;
  }

  public TreeBackedResolvedType resolve(TreePath referencingPath) {
    return new ResolvedElementQualifiedNameableTreePathScannerForTypeResolution()
        .scan(referencingPath, null);
  }

  private interface Remediation {
    boolean canBeApplied();

    @Nullable
    String getMessage();
  }

  public class TreeBackedResolvedType {
    public final TreePath location;
    public final QualifiedNameable referencedElement;
    public final QualifiedNameable canonicalElement;
    @Nullable public final TreeBackedResolvedType enclosingElement;
    private final Scope scope;
    @Nullable private final String message;
    private final List<Remediation> remediations;

    public TreeBackedResolvedType(
        TreePath referencingPath,
        QualifiedNameable canonicalElement,
        @Nullable TreeBackedResolvedType enclosingElement) {
      this.location = referencingPath;
      this.canonicalElement = canonicalElement;
      this.referencedElement =
          (QualifiedNameable) Objects.requireNonNull(trees.getElement(referencingPath));
      this.enclosingElement = enclosingElement;
      scope = Objects.requireNonNull(trees.getScope(location));
      try {
        remediations = new ArrayList<>();
        if (!isCanonicalReference()) {
          message =
              "Source-only ABI generation requires that this type be referred to by its canonical name.";
          if (enclosingElement == null) {
            remediations.add(new ImportTypeRemediation((TypeElement) canonicalElement));
          }
          remediations.add(new CanonicalizeReferenceRemediation());
        } else if (!isResolvable()) {
          TypeElement referencedType = (TypeElement) referencedElement;
          if (imports != null && imports.isOnDemandImported(referencedElement)
              || (imports != null
                  && imports.isOnDemandStaticImported(referencedType)
                  && imports.getOnDemandStaticImportContainer(referencedType)
                      == referencedType.getEnclosingElement())) {
            message =
                "Source-only ABI generation requires that this type be explicitly imported (star imports are not accepted).";
            remediations.add(new ImportTypeRemediation((TypeElement) referencedElement));
          } else if (imports != null
              && (imports.isOnDemandStaticImported(referencedType)
                  || imports.isStaticImported(referencedType))) {
            message = null;
            remediations.add(new CannotFixRemediation());
          } else {
            message =
                "Source-only ABI generation requires that this member type reference be more explicit.";
            remediations.add(
                new ImportTypeRemediation((TypeElement) referencedElement.getEnclosingElement()));
            remediations.add(new QualifyNameRemediation(referencedType));
          }
        } else if (!nameIsCorrectCase()) {
          message =
              referencedElement.accept(
                  new SimpleElementVisitor8<String, Void>() {
                    @Override
                    public String visitPackage(PackageElement e, Void aVoid) {
                      remediations.add(
                          new RenameRemediation(
                              e.getSimpleName(), fixNameCase(e, Character::toLowerCase)));
                      return "Source-only ABI generation requires package names to start with a lowercase letter.";
                    }

                    @Override
                    public String visitType(TypeElement e, Void aVoid) {
                      remediations.add(
                          new RenameRemediation(
                              e.getSimpleName(), fixNameCase(e, Character::toUpperCase)));
                      return "Source-only ABI generation requires top-level class names to start with a capital letter.";
                    }

                    @Override
                    protected String defaultAction(Element e, Void aVoid) {
                      throw new IllegalArgumentException(
                          String.format("Unexpected element of kind %s: %s", e.getKind(), e));
                    }
                  },
                  null);
        } else {
          message = null;
        }
      } catch (RuntimeException e) {
        throw new RuntimeException(
            String.format("When resolving %s at %s.", referencedElement, location), e);
      }
    }

    public boolean isCorrect() {
      boolean enclosingIsCorrect = enclosingElement == null || enclosingElement.isCorrect();

      return enclosingIsCorrect && isCanonicalReference() && isResolvable() && nameIsCorrectCase();
    }

    public boolean isCorrectable() {
      boolean enclosingIsCorrectable = enclosingElement == null || enclosingElement.isCorrectable();

      return enclosingIsCorrectable
          && remediations.stream().map(Remediation::canBeApplied).reduce(true, Boolean::logicalAnd);
    }

    public void reportErrors(Diagnostic.Kind messageKind) {
      String errorMessage = getErrorMessage();
      if (errorMessage != null) {
        trees.printMessage(
            messageKind, errorMessage, location.getLeaf(), location.getCompilationUnit());
      }

      if (enclosingElement != null) {
        enclosingElement.reportErrors(messageKind);
      }
    }

    @Nullable
    public String getErrorMessage() {
      if (message == null) {
        return null;
      }
      return String.format(
          "%s\nTo fix: \n%s\n",
          message,
          remediations.stream().map(Remediation::getMessage).collect(Collectors.joining("\n")));
    }

    private boolean isCanonicalReference() {
      return referencedElement == canonicalElement;
    }

    private boolean isResolvable() {
      if (location.getLeaf().getKind() == Kind.MEMBER_SELECT) {
        return true;
      } else if (referencedElement.getKind() == ElementKind.PACKAGE) {
        return true;
      }

      TypeElement referencedType = (TypeElement) referencedElement;
      return (imports != null && imports.isSingleTypeImported(referencedType))
          || isTopLevelInReferencingPackage(referencedType);
    }

    private boolean nameIsCorrectCase() {
      return referencedElement.accept(
          new SimpleElementVisitor8<Boolean, Void>() {
            @Override
            public Boolean visitType(TypeElement e, Void aVoid) {
              return e.getNestingKind() != NestingKind.TOP_LEVEL
                  || Character.isUpperCase(e.getSimpleName().toString().charAt(0));
            }

            @Override
            public Boolean visitPackage(PackageElement e, Void aVoid) {
              return Character.isLowerCase(e.getSimpleName().toString().charAt(0));
            }

            @Override
            protected Boolean defaultAction(Element e, Void aVoid) {
              throw new IllegalArgumentException(
                  String.format("Unexpected element of kind %s: %s", e.getKind(), e));
            }
          },
          null);
    }

    private String fixNameCase(Element element, Function<Character, Character> fix) {
      String simpleName = element.getSimpleName().toString();
      char firstLetter = simpleName.charAt(0);
      return String.format("%c%s", fix.apply(firstLetter), simpleName.substring(1));
    }

    private class QualifyNameRemediation implements Remediation {
      private final TypeElement toQualify;

      private QualifyNameRemediation(TypeElement toQualify) {
        this.toQualify = toQualify;
      }

      @Override
      public boolean canBeApplied() {
        return true;
      }

      @Override
      public String getMessage() {
        TypeElement enclosingType = (TypeElement) toQualify.getEnclosingElement();
        return String.format(
            "Use \"%s.%s\" here instead of \"%s\".",
            enclosingType.getSimpleName(), toQualify.getSimpleName(), toQualify.getSimpleName());
      }
    }

    private class ImportTypeRemediation implements Remediation {
      private final TypeElement toImport;

      private ImportTypeRemediation(TypeElement toImport) {
        this.toImport = toImport;
      }

      @Override
      public boolean canBeApplied() {
        return trees.isAccessible(scope, toImport)
            && !Objects.requireNonNull(imports).nameIsStaticImported(toImport.getSimpleName());
      }

      @Override
      public String getMessage() {
        return String.format("Add an import for \"%s\"", toImport.getQualifiedName());
      }
    }

    private class CanonicalizeReferenceRemediation implements Remediation {
      @Override
      public boolean canBeApplied() {
        return trees.isAccessible(scope, (TypeElement) canonicalElement);
      }

      @Override
      @Nullable
      public String getMessage() {
        if (canonicalElement == referencedElement) {
          return null;
        }
        return String.format(
            "Use \"%s\" here instead of \"%s\".",
            canonicalElement.getSimpleName(), referencedElement.getSimpleName());
      }
    }

    private class CannotFixRemediation implements Remediation {
      @Override
      public boolean canBeApplied() {
        return false;
      }

      @Override
      @Nullable
      public String getMessage() {
        return null;
      }
    }
  }

  private static class RenameRemediation implements Remediation {
    private final CharSequence from;
    private final CharSequence to;

    public RenameRemediation(CharSequence from, CharSequence to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public boolean canBeApplied() {
      return true;
    }

    @Override
    public String getMessage() {
      return String.format("Rename \"%s\" to \"%s\".", from, to);
    }
  }

  private class ResolvedElementQualifiedNameableTreePathScannerForTypeResolution
      extends TreePathScannerForTypeResolution<TreeBackedResolvedType, QualifiedNameable> {

    public ResolvedElementQualifiedNameableTreePathScannerForTypeResolution() {
      super(TreeBackedTypeResolutionSimulator.this.trees);
    }

    @Override
    protected TreeBackedResolvedType resolveType(
        TreePath referencingPath, TypeElement referencedType, QualifiedNameable canonicalType) {
      if (canonicalType == null) {
        canonicalType = referencedType;
      }
      return new TreeBackedResolvedType(
          referencingPath,
          canonicalType,
          resolveEnclosingElement((QualifiedNameable) canonicalType.getEnclosingElement()));
    }

    @Override
    protected TreeBackedResolvedType resolvePackage(
        TreePath referencingPath,
        PackageElement referencedPackage,
        QualifiedNameable canonicalPackage) {
      // PackageElements are not considered to be enclosed by their parent elements, but
      // our logic is a lot simpler if we can pretend they are.
      TreeBackedResolvedType enclosingElement = null;
      String qualifiedName = canonicalPackage.getQualifiedName().toString();
      int lastDot = qualifiedName.lastIndexOf('.');
      if (lastDot > 0) {
        String enclosingPackageQualifiedName = qualifiedName.substring(0, lastDot);
        PackageElement enclosingPackage =
            Objects.requireNonNull(elements.getPackageElement(enclosingPackageQualifiedName));
        enclosingElement = resolveEnclosingElement(enclosingPackage);
      }

      return new TreeBackedResolvedType(referencingPath, canonicalPackage, enclosingElement);
    }
  }
}
