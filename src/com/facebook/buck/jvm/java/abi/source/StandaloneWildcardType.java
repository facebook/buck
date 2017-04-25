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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * An implementation of {@link WildcardType} that is not dependent on any particular compiler
 * implementation. It requires a {@link TypeMirror}, but does not depend on any particular
 * implementation of it (beyond the spec).
 */
class StandaloneWildcardType extends StandaloneTypeMirror implements WildcardType {
  @Nullable private final TypeMirror extendsBound;
  @Nullable private final TypeMirror superBound;

  public StandaloneWildcardType(
      @Nullable TypeMirror extendsBound, @Nullable TypeMirror superBound) {
    super(TypeKind.WILDCARD);
    this.extendsBound = extendsBound;
    this.superBound = superBound;
  }

  @Override
  @Nullable
  public TypeMirror getExtendsBound() {
    return extendsBound;
  }

  @Override
  @Nullable
  public TypeMirror getSuperBound() {
    return superBound;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();

    builder.append('?');
    if (extendsBound != null) {
      builder.append(" extends ");
      builder.append(extendsBound.toString());
    } else if (superBound != null) {
      builder.append(" super ");
      builder.append(superBound.toString());
    }

    return builder.toString();
  }
}
