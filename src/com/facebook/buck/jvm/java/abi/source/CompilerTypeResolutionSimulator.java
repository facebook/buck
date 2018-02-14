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

import com.facebook.buck.jvm.java.lang.model.MoreElements;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.PropagatesNullable;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.NestingKind;
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

    return new TreePathScanner<ResolvedType, Void>() {
      @Override
      public ResolvedType visitAnnotatedType(AnnotatedTypeTree node, Void aVoid) {
        return scan(node.getUnderlyingType(), aVoid);
      }

      @Override
      public ResolvedType visitParameterizedType(ParameterizedTypeTree node, Void aVoid) {
        return scan(node.getType(), aVoid);
      }

      @Override
      public ResolvedType visitIdentifier(IdentifierTree node, Void aVoid) {
        TypeElement typeElement = (TypeElement) trees.getElement(getCurrentPath());
        return newResolvedType(typeElement);
      }

      private ResolvedType newResolvedType(TypeElement typeElement) {
        ResolvedTypeKind kind = ResolvedTypeKind.RESOLVED_TYPE;
        Set<String> missingDependencies = new HashSet<>();

        if (!fileManager.typeWillBeAvailable(typeElement)) {
          kind = ResolvedTypeKind.ERROR_TYPE;
          missingDependencies.add(fileManager.getOwningTarget(typeElement));
        }

        return new ResolvedType(kind, typeElement, missingDependencies);
      }

      @Override
      public ResolvedType visitMemberSelect(MemberSelectTree node, Void aVoid) {
        TypeElement type = (TypeElement) trees.getElement(getCurrentPath());
        return new Object() {
          private ResolvedTypeKind kind = ResolvedTypeKind.RESOLVED_TYPE;
          private Set<String> missingDependencies = new HashSet<>();

          {
            CompletionSimulator.CompletedType completedType = completer.complete(type, false);
            if (completedType.kind != CompletedTypeKind.COMPLETED_TYPE) {
              kind = kind.merge(ResolvedTypeKind.ERROR_TYPE);
              missingDependencies.addAll(completedType.getMissingDependencies());
            }
          }

          public ResolvedType resolve() {
            if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
              ResolvedType referencedEnclosingType = scan(node.getExpression(), aVoid);
              missingDependencies.addAll(referencedEnclosingType.missingDependencies);
              resolveMemberType(type, referencedEnclosingType.type);

              if (referencedEnclosingType.kind == ResolvedTypeKind.ERROR_TYPE) {
                // If the originally referenced enclosing type was not found, the compiler would
                // actually have stopped right there. We kept going because we wanted to get all
                // the things that would be necessary to convert that error type into a resolved
                // type
                kind = ResolvedTypeKind.ERROR_TYPE;
              }
            }
            return new ResolvedType(kind, type, missingDependencies);
          }

          private boolean resolveMemberType(
              TypeElement type, @Nullable TypeElement referencedEnclosingType) {
            if (referencedEnclosingType == null) {
              // Ran out of places to look
              return false;
            }

            if (type.getEnclosingElement() == referencedEnclosingType) {
              // Found it!
              return true;
            }

            TypeElement superclass = MoreElements.getSuperclass(referencedEnclosingType);
            markAsCrashIfNotResolvable(superclass);
            if (resolveMemberType(type, superclass)) {
              return true;
            }

            Iterable<TypeElement> interfaces =
                MoreElements.getInterfaces(referencedEnclosingType)::iterator;
            for (TypeElement interfaceElement : interfaces) {
              markAsCrashIfNotResolvable(interfaceElement);
              if (resolveMemberType(type, interfaceElement)) {
                return true;
              }
            }

            return false;
          }

          private void markAsCrashIfNotResolvable(@Nullable TypeElement type) {
            if (type == null) {
              return;
            }

            CompletionSimulator.CompletedType completedType = completer.complete(type, false);
            if (completedType.kind != CompletedTypeKind.COMPLETED_TYPE) {
              kind = kind.merge(ResolvedTypeKind.CRASH);
              missingDependencies.addAll(completedType.getMissingDependencies());
            }
          }
        }.resolve();
      }
    }.scan(referencingPath, null);
  }
}
