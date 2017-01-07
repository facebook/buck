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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVisitor;

/**
 * An implementation of {@link PrimitiveType} that does not depend on the compiler.
 */
class StandalonePrimitiveType extends StandaloneTypeMirror implements PrimitiveType {
  public static final Map<TypeKind, StandalonePrimitiveType> INSTANCES =
      Collections.unmodifiableMap(
          new HashMap<TypeKind, StandalonePrimitiveType>() {{
            for (TypeKind typeKind : TypeKind.values()) {
              if (typeKind.isPrimitive()) {
                put(typeKind, new StandalonePrimitiveType(typeKind));
              }
            }
          }});

  private StandalonePrimitiveType(TypeKind kind) {
    super(kind);

    if (!kind.isPrimitive()) {
      throw new IllegalArgumentException();
    }
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return getKind().toString().toLowerCase();
  }
}
