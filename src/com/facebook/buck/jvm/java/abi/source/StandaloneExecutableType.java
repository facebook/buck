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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

/**
 * An implementation of {@link ExecutableType} that is not dependent on any particular compiler
 * implementation. It requires {@link javax.lang.model.element.Element} and {@link TypeMirror}
 * objects, but does not depend on any particular implementation of them (beyond the spec).
 */
class StandaloneExecutableType extends StandaloneTypeMirror implements ExecutableType {
  private final TypeMirror returnType;
  private final List<? extends TypeVariable> typeVariables;
  private final List<? extends TypeMirror> parameterTypes;
  private final List<? extends TypeMirror> thrownTypes;

  public StandaloneExecutableType(
      TypeMirror returnType,
      List<? extends TypeVariable> typeVariables,
      List<? extends TypeMirror> parameterTypes,
      List<? extends TypeMirror> thrownTypes,
      List<? extends AnnotationMirror> annotations) {
    super(TypeKind.EXECUTABLE, annotations);
    this.returnType = returnType;
    this.typeVariables = Collections.unmodifiableList(new ArrayList<>(typeVariables));
    this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes));
    this.thrownTypes = Collections.unmodifiableList(new ArrayList<>(thrownTypes));
  }

  @Override
  public List<? extends TypeVariable> getTypeVariables() {
    return typeVariables;
  }

  @Override
  public TypeMirror getReturnType() {
    return returnType;
  }

  @Override
  public List<? extends TypeMirror> getParameterTypes() {
    return parameterTypes;
  }

  @Override
  @Nullable
  public TypeMirror getReceiverType() {
    // TODO(jkeljo): Either implement this adaptively or wait until we eliminate javac 7 support
    return null;
  }

  @Override
  public List<? extends TypeMirror> getThrownTypes() {
    return thrownTypes;
  }
}
