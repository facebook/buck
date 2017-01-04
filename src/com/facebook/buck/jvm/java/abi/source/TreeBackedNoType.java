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
 * An implementation of {@link NoType} that uses only the information available in one or
 * more {@link javax.lang.model.element.Element} objects. The implementation of the
 * {@link javax.lang.model.element.Element} does not matter; it can be tree-backed or from
 * the compiler.
 */
class TreeBackedNoType extends TreeBackedTypeMirror implements NoType {
  public static final TreeBackedNoType KIND_NONE = new TreeBackedNoType(TypeKind.NONE);
  public static final TreeBackedNoType KIND_PACKAGE = new TreeBackedNoType(TypeKind.PACKAGE);
  public static final TreeBackedNoType KIND_VOID = new TreeBackedNoType(TypeKind.VOID);

  private final TypeKind kind;

  private TreeBackedNoType(TypeKind kind) {
    this.kind = kind;
  }

  @Override
  public TypeKind getKind() {
    return kind;
  }

  @Override
  public <R, P> R accept(TypeVisitor<R, P> v, P p) {
    throw new UnsupportedOperationException();
  }
}
