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

import com.facebook.buck.jvm.java.abi.source.CompletionSimulator.CompletedType;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.facebook.buck.util.liteinfersupport.PropagatesNullable;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

/**
 * Simulates how {@code javac} resolves types, at least so far as to determine whether a given
 * resolution would succeed or not under source-only ABI.
 */
class CompilerTypeResolutionSimulator {

  private final Trees trees;
  private final FileManagerSimulator fileManager;
  private final CompletionSimulator completer;

  @Nullable private ImportsTracker imports = null;

  public CompilerTypeResolutionSimulator(Trees trees, FileManagerSimulator fileManager) {
    this.trees = trees;
    this.fileManager = fileManager;
    this.completer = new CompletionSimulator(fileManager);
  }

  public void setImports(ImportsTracker imports) {
    this.imports = imports;
  }

  public ResolvedType resolve(@PropagatesNullable TreePath referencingPath) {
    if (referencingPath == null) {
      return null;
    }

    return new TreePathScannerForTypeResolution<ResolvedType, Void>(trees) {
      @Override
      protected ResolvedType resolveType(
          TreePath referencingPath, TypeElement referencedType, Void aVoid) {
        Set<String> missingDependencies = new HashSet<>();
        ResolvedTypeKind kind;
        if (referencedType.getNestingKind() == NestingKind.TOP_LEVEL) {
          kind =
              fileManager.typeWillBeAvailable(referencedType)
                  ? ResolvedTypeKind.RESOLVED_TYPE
                  : ResolvedTypeKind.ERROR_TYPE;
        } else {
          ResolvedType enclosingReference = resolveEnclosingElement(aVoid);
          TypeElement enclosingType;
          if (enclosingReference != null) {
            missingDependencies.addAll(enclosingReference.missingDependencies);
            enclosingType = enclosingReference.type;
          } else if (fileManager.isCompiledInCurrentRun(referencedType)) {
            return new ResolvedType(
                ResolvedTypeKind.RESOLVED_TYPE, referencedType, Collections.emptySet());
          } else if (imports != null
              && (imports.isSingleTypeImported(referencedType)
                  || imports.isOnDemandImported(referencedType))) {
            enclosingType =
                (TypeElement) Preconditions.checkNotNull(referencedType.getEnclosingElement());
          } else if (imports != null && imports.isStaticImported(referencedType)) {
            enclosingType =
                Preconditions.checkNotNull(imports.getStaticImportContainer(referencedType));
          } else if (imports != null && imports.isOnDemandStaticImported(referencedType)) {
            enclosingType =
                Preconditions.checkNotNull(
                    imports.getOnDemandStaticImportContainer(referencedType));
          } else {
            return new ResolvedType(
                ResolvedTypeKind.ERROR_TYPE, referencedType, Collections.emptySet());
          }

          CompletedType completedEnclosingType =
              Preconditions.checkNotNull(completer.complete(enclosingType));
          missingDependencies.addAll(completedEnclosingType.getMissingDependencies());
          if (enclosingReference != null
              && enclosingReference.kind == ResolvedTypeKind.ERROR_TYPE) {
            // Once we're in error-type land, the compiler won't really look at the rest of things
            kind = ResolvedTypeKind.ERROR_TYPE;
          } else {
            switch (completedEnclosingType.kind) {
              case COMPLETED_TYPE:
                kind = ResolvedTypeKind.RESOLVED_TYPE;
                break;
              case PARTIALLY_COMPLETED_TYPE:
              case ERROR_TYPE:
                // In the case of a partially-completed type we haven't done enough work to know
                // whether the resolution would succeed, so we'll be conservative and assume that
                // it wouldn't
                kind = ResolvedTypeKind.ERROR_TYPE;
                break;
              case CRASH:
                kind = ResolvedTypeKind.CRASH;
                break;
              default:
                throw new IllegalArgumentException();
            }
          }
        }
        return new ResolvedType(kind, referencedType, missingDependencies);
      }

      @Override
      @Nullable
      protected ResolvedType resolvePackage(
          TreePath referencingPath, PackageElement referencedPackage, Void aVoid) {
        throw new AssertionError();
      }
    }.scan(referencingPath, null);
  }
}
