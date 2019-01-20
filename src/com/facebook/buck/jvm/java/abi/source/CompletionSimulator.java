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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Simulates {@code javac}'s symbol completion logic for a given type under the source-only ABI
 * classpath to determine what would be required for it to succeed.
 */
class CompletionSimulator {

  private final FileManagerSimulator fileManager;

  public CompletionSimulator(FileManagerSimulator fileManager) {
    this.fileManager = fileManager;
  }

  @Nullable
  public CompletedType complete(Element element, boolean transitive) {
    ElementKind kind = element.getKind();
    if (!kind.isClass() && !kind.isInterface()) {
      return null;
    }

    return complete((TypeElement) element, transitive);
  }

  public CompletedType complete(TypeElement type, boolean transitive) {
    return new CompletedType(type, transitive);
  }

  @Nullable
  private CompletedType complete(TypeMirror type, boolean transitive) {
    if (type.getKind() != TypeKind.DECLARED) {
      return null;
    }

    DeclaredType declaredType = (DeclaredType) type;
    TypeElement element = (TypeElement) declaredType.asElement();
    return complete(element, transitive);
  }

  public class CompletedType {

    public final TypeElement element;
    @Nullable public final CompletedType enclosingType;
    @Nullable public final CompletedType superclass;
    public final List<CompletedType> interfaces;
    public final CompletedTypeKind kind;

    @Nullable private List<String> missingDependencies = null;

    private CompletedType(TypeElement element, boolean transitive) {
      this.element = element;
      enclosingType = complete(element.getEnclosingElement(), transitive);

      if (transitive) {
        superclass = complete(element.getSuperclass(), transitive);
        interfaces =
            element
                .getInterfaces()
                .stream()
                .map(it -> complete(it, transitive))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
      } else {
        superclass = null;
        interfaces = Collections.emptyList();
      }

      this.kind = computeKind();
    }

    private CompletedTypeKind computeKind() {
      CompletedTypeKind kind = CompletedTypeKind.COMPLETED_TYPE;
      if (!fileManager.typeWillBeAvailable(element)) {
        kind = CompletedTypeKind.ERROR_TYPE;
      }
      if (enclosingType != null) {
        kind = enclosingType.kind;
      }
      if (kind == CompletedTypeKind.ERROR_TYPE) {
        return kind;
      }

      kind =
          kind.merge(
              superAndInterfacesStream()
                  .map(type -> type.kind)
                  .map(
                      typeKind -> {
                        if (typeKind != CompletedTypeKind.ERROR_TYPE) {
                          return typeKind;
                        }
                        return fileManager.isCompiledInCurrentRun(element)
                            ? CompletedTypeKind.PARTIALLY_COMPLETED_TYPE
                            : CompletedTypeKind.CRASH;
                      })
                  .reduce(CompletedTypeKind.COMPLETED_TYPE, CompletedTypeKind::merge));
      return kind;
    }

    private boolean superclassesMayBeMissing() {
      return fileManager.isCompiledInCurrentRun(element);
    }

    /**
     * Returns the list of build target dependencies that must be added so that completion can get
     * one level further with this element. That is, move from {@link CompletedTypeKind#CRASH}/
     * {@link CompletedTypeKind#ERROR_TYPE} to {@link CompletedTypeKind#PARTIALLY_COMPLETED_TYPE},
     * or from {@link CompletedTypeKind#PARTIALLY_COMPLETED_TYPE} to {@link
     * CompletedTypeKind#COMPLETED_TYPE}.
     */
    public List<String> getMissingDependencies() {
      if (missingDependencies == null) {
        missingDependencies = new ArrayList<>();

        if (kind == CompletedTypeKind.ERROR_TYPE) {
          // Realistically the only way we get to be an error type is if our class itself is
          // missing.
          missingDependencies.add(fileManager.getOwningTarget(element));
        }

        // Our kind will always be at least as "bad" as our enclosing type, so if it's holding
        // us back we must move it forward too.
        if (enclosingType != null && enclosingType.kind == kind) {
          missingDependencies.addAll(enclosingType.getMissingDependencies());
        }

        superAndInterfacesStream()
            .filter(
                supertype -> {
                  if (kind == CompletedTypeKind.PARTIALLY_COMPLETED_TYPE) {
                    // If this type is partially completed, it's because supers are missing, so we
                    // want to fill in their missing deps.
                    return true;
                  } else if (kind == CompletedTypeKind.COMPLETED_TYPE) {
                    // Small optimization -- if this type is completed, there are no missing deps by
                    // definition.
                    return false;
                  } else if (!superclassesMayBeMissing()
                      && supertype.kind == CompletedTypeKind.ERROR_TYPE) {
                    // If this type is crashing due to missing supers, we want it to stop
                    // If it's an error type, we don't want it to start crashing due to missing
                    // supers
                    // once it becomes available.
                    return true;
                  }

                  // Any crash of a supertype will crash the immediate subtype, so we want to add
                  // deps to prevent that
                  return supertype.kind == CompletedTypeKind.CRASH;
                })
            .map(CompletedType::getMissingDependencies)
            .forEach(missingDependencies::addAll);
      }

      return missingDependencies;
    }

    private Stream<CompletedType> superAndInterfacesStream() {
      return Stream.concat(Stream.of(superclass), interfaces.stream()).filter(Objects::nonNull);
    }
  }
}
