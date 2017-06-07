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

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;

/**
 * An implementation of {@link TypeVariable} that is not dependent on any particular compiler
 * implementation. It requires {@link javax.lang.model.element.Element} and {@link TypeMirror}
 * objects, but does not depend on any particular implementation of them (beyond the spec).
 */
class StandaloneTypeVariable extends StandaloneTypeMirror implements TypeVariable {

  private final TypeParameterElement element;
  private final NullType lowerBound;
  @Nullable private TypeMirror upperBound;

  public StandaloneTypeVariable(Types types, TypeParameterElement element) {
    this(types, element, Collections.emptyList());
  }

  public StandaloneTypeVariable(
      Types types, TypeParameterElement element, List<? extends AnnotationMirror> annotations) {
    super(TypeKind.TYPEVAR, annotations);
    this.element = element;
    lowerBound = types.getNullType();
  }

  @Override
  public Element asElement() {
    return element;
  }

  @Override
  public TypeMirror getUpperBound() {
    if (upperBound == null) {
      final List<? extends TypeMirror> bounds = element.getBounds();

      if (bounds.size() == 1) {
        upperBound = bounds.get(0);
      } else {
        upperBound = new StandaloneIntersectionType(bounds);
      }
    }
    return upperBound;
  }

  @Override
  public TypeMirror getLowerBound() {
    // TODO(jkeljo): Capture conversion can create a non-null lower bound, but we don't need it yet.
    return lowerBound;
  }

  @Override
  public String toString() {
    return element.toString();
  }
}
