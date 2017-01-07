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

import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVisitor;

/**
 * An implementation of {@link NullType} that does not depend on the compiler.
 */
class StandaloneNullType extends StandaloneTypeMirror implements NullType {
  public static final StandaloneNullType INSTANCE = new StandaloneNullType();

  private StandaloneNullType() {
    super(TypeKind.NULL);
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return "<nulltype>";
  }
}
