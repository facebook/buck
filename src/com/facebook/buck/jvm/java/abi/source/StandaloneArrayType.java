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
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link ArrayType} that is not dependent on any particular compiler
 * implementation. It requires a {@link TypeMirror}, but does not depend on any particular
 * implementation of it (beyond the spec).
 */
class StandaloneArrayType extends StandaloneTypeMirror implements ArrayType {
  private final TypeMirror componentType;

  public StandaloneArrayType(TypeMirror componentType) {
    this(componentType, Collections.emptyList());
  }

  public StandaloneArrayType(
      TypeMirror componentType, List<? extends AnnotationMirror> annotations) {
    super(TypeKind.ARRAY, annotations);
    this.componentType = componentType;
  }

  @Override
  public TypeMirror getComponentType() {
    return componentType;
  }

  @Override
  public String toString() {
    return String.format("%s[]", componentType.toString());
  }
}
