/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.starlark.compatible;

import com.google.common.primitives.Primitives;
import java.lang.annotation.Annotation;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;

/**
 * An instance of the skylark annotation that we create and pass around to piggy-back off skylark
 * functions.
 */
@SuppressWarnings("all")
class BuckStarlarkParam {

  private final String name;
  private final Class<?> type;
  private final String defaultSkylarkValue;

  private BuckStarlarkParam(String name, Class<?> type, String defaultSkylarkValue) {
    this.name = name;
    this.type = type;
    this.defaultSkylarkValue = defaultSkylarkValue;
  }

  /**
   * @param parameter the parameter type class
   * @param namedParameter the name of the parameter, if any
   * @param defaultSkylarkValue the string represetnation of the default skylark value
   * @return an instance of the skylark annotation representing a parameter of the given type and
   *     name
   */
  static BuckStarlarkParam fromParam(
      Class<?> parameter, @Nullable String namedParameter, @Nullable String defaultSkylarkValue) {
    if (namedParameter == null) {
      namedParameter = "";
    }
    if (defaultSkylarkValue == null) {
      defaultSkylarkValue = "";
    }
    Class<?> type = parameter;
    if (type.isPrimitive()) {
      type = Primitives.wrap(type);
    }
    return new BuckStarlarkParam(namedParameter, type, defaultSkylarkValue);
  }

  public Class<? extends Annotation> annotationType() {
    return Param.class;
  }

  public String name() {
    return name;
  }

  public Class<?> type() {
    return type;
  }

  public String defaultValue() {
    return defaultSkylarkValue;
  }

  public boolean named() {
    return !name.isEmpty();
  }
}
