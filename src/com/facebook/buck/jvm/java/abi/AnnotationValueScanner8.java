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

package com.facebook.buck.jvm.java.abi;

import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.AbstractAnnotationValueVisitor8;

public class AnnotationValueScanner8<R, P> extends AbstractAnnotationValueVisitor8<R, P> {
  @Nullable private final R defaultValue;

  protected AnnotationValueScanner8() {
    this(null);
  }

  protected AnnotationValueScanner8(@Nullable R defaultValue) {
    this.defaultValue = defaultValue;
  }

  @Nullable
  public final R scan(Iterable<? extends AnnotationValue> iterable, P p) {
    R result = defaultValue;
    for (AnnotationValue value : iterable) {
      result = scan(value, p);
    }
    return result;
  }

  public R scan(AnnotationValue value, P p) {
    return value.accept(this, p);
  }

  public final R scan(AnnotationValue value) {
    return value.accept(this, null);
  }

  @Override
  @Nullable
  public R visitBoolean(boolean b, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitByte(byte b, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitChar(char c, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitDouble(double d, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitFloat(float f, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitInt(int i, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitLong(long i, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitShort(short s, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitString(String s, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitType(TypeMirror t, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitEnumConstant(VariableElement c, P p) {
    return defaultValue;
  }

  @Override
  @Nullable
  public R visitAnnotation(AnnotationMirror a, P p) {
    return scan(a.getElementValues().values(), p);
  }

  @Override
  @Nullable
  public R visitArray(List<? extends AnnotationValue> vals, P p) {
    return scan(vals, p);
  }

  @Override
  @Nullable
  public R visitUnknown(AnnotationValue av, P p) {
    return defaultValue;
  }
}
