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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link IntersectionType} that is not dependent on any particular compiler
 * implementation. It requires a {@link TypeMirror}, but does not depend on any particular
 * implementation of it (beyond the spec).
 */
class StandaloneIntersectionType extends StandaloneTypeMirror implements IntersectionType {
  private final List<TypeMirror> bounds;

  public StandaloneIntersectionType(List<? extends TypeMirror> bounds) {
    super(TypeKind.INTERSECTION);

    this.bounds = Collections.unmodifiableList(new ArrayList<>(bounds));
  }

  @Override
  public List<? extends TypeMirror> getBounds() {
    return bounds;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("java.lang.Object");

    for (TypeMirror bound : bounds) {
      builder.append("&");
      builder.append(bound.toString());
    }

    return builder.toString();
  }
}
