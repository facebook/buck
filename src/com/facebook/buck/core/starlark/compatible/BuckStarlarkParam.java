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
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import java.lang.annotation.Annotation;
import javax.annotation.Nullable;

/**
 * An instance of the skylark annotation that we create and pass around to piggy-back off skylark
 * functions.
 */
@SuppressWarnings("all")
class BuckStarlarkParam implements Param {

  public static final BuckStarlarkParam NONE = new BuckStarlarkParam("", Object.class, "", true);

  private final String name;
  private final Class<?> type;
  private final String defaultSkylarkValue;
  private final boolean noneable;

  private BuckStarlarkParam(
      String name, Class<?> type, String defaultSkylarkValue, boolean noneable) {
    this.name = name;
    this.type = type;
    this.defaultSkylarkValue = defaultSkylarkValue;
    this.noneable = noneable;
  }

  /**
   * @param parameter the parameter type class
   * @param namedParameter the name of the parameter, if any
   * @param defaultSkylarkValue the string represetnation of the default skylark value
   * @param noneable whether this parameter can accept `None`
   * @return an instance of the skylark annotation representing a parameter of the given type and
   *     name
   */
  static BuckStarlarkParam fromParam(
      Class<?> parameter,
      @Nullable String namedParameter,
      @Nullable String defaultSkylarkValue,
      boolean noneable) {
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
    return new BuckStarlarkParam(namedParameter, type, defaultSkylarkValue, noneable);
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return Param.class;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Class<?> type() {
    return type;
  }

  @Override
  public Class<?> generic1() {
    return Object.class;
  }

  @Override
  public boolean noneable() {
    return noneable;
  }

  @Override
  public String doc() {
    return "";
  }

  @Override
  public String defaultValue() {
    return defaultSkylarkValue;
  }

  @Override
  public ParamType[] allowedTypes() {
    return new ParamType[] {};
  }

  @Override
  public boolean callbackEnabled() {
    return false;
  }

  @Override
  public boolean named() {
    return !name.isEmpty();
  }

  @Override
  public boolean legacyNamed() {
    return false;
  }

  @Override
  public boolean positional() {
    return true;
  }

  @Override
  public StarlarkSemantics.FlagIdentifier enableOnlyWithFlag() {
    return StarlarkSemantics.FlagIdentifier.NONE;
  }

  @Override
  public StarlarkSemantics.FlagIdentifier disableWithFlag() {
    return StarlarkSemantics.FlagIdentifier.NONE;
  }

  @Override
  public String valueWhenDisabled() {
    return "";
  }
}
