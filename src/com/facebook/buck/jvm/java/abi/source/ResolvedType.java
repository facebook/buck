/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import java.util.Collections;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/**
 * Represents a resolved type as simulated by {@link
 * com.facebook.buck.jvm.java.abi.source.CompilerTypeResolutionSimulator}.
 */
class ResolvedType {
  public final ResolvedTypeKind kind;
  public final TypeElement type;
  public final Set<String> missingDependencies;

  public ResolvedType(ResolvedTypeKind kind, TypeElement type) {
    this(kind, type, Collections.emptySet());
  }

  public ResolvedType(ResolvedTypeKind kind, TypeElement type, Set<String> missingDependencies) {
    this.kind = kind;
    this.type = type;
    this.missingDependencies = missingDependencies;
  }
}
