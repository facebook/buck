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

import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVisitor;

/**
 * An implementation of {@link NoType} that does not depend on any particular compiler
 * implementation.
 */
class StandaloneNoType extends StandaloneTypeMirror implements NoType {
  public static final StandaloneNoType KIND_NONE = new StandaloneNoType(TypeKind.NONE);
  public static final StandaloneNoType KIND_PACKAGE = new StandaloneNoType(TypeKind.PACKAGE);
  public static final StandaloneNoType KIND_VOID = new StandaloneNoType(TypeKind.VOID);

  private StandaloneNoType(TypeKind kind) {
    super(kind);
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }
}
