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

import java.lang.annotation.Annotation;
import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link TypeMirror} that is not dependent on any particular compiler
 * implementation. Subclasses may require {@link javax.lang.model.element.Element}s and/or
 * {@link javax.lang.model.type.TypeMirror}s, but do not depend on any particular implementation of
 * them (beyond the spec).
 */
abstract class StandaloneTypeMirror implements TypeMirror {
  private final TypeKind kind;

  protected StandaloneTypeMirror(TypeKind kind) {
    this.kind = kind;
  }

  @Override
  public TypeKind getKind() {
    return kind;
  }

  @Override
  public List<? extends AnnotationMirror> getAnnotationMirrors() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    throw new UnsupportedOperationException();
  }
}
