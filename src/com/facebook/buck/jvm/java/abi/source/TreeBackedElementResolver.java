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

import javax.lang.model.type.TypeMirror;

/**
 * Used to resolve type references in {@link TreeBackedElement}s after they've all been created.
 */
class TreeBackedElementResolver {
  private final TreeBackedTypes types;

  public TreeBackedElementResolver(TreeBackedTypes types) {
    this.types = types;
  }

  /* package */ StandaloneDeclaredType createType(TreeBackedTypeElement element) {
    return new StandaloneDeclaredType(types, element);
  }

  /* package */ StandaloneTypeVariable createType(TreeBackedTypeParameterElement element) {
    return new StandaloneTypeVariable(types, element);
  }

  /* package */ StandalonePackageType createType(TreeBackedPackageElement element) {
    return new StandalonePackageType(element);
  }

  /* package */ TypeMirror getCanonicalType(TypeMirror javacType) {
    return types.getCanonicalType(javacType);
  }
}
