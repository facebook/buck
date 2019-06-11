/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.compatible;

import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.util.List;

/**
 * An instance of the skylark annotation that we create and pass around to piggy-back off skylark
 * functions.
 */
@SuppressWarnings("all")
class BuckStarlarkCallable implements SkylarkCallable {

  private final String methodName;
  private final Param[] skylarkParams;

  private BuckStarlarkCallable(String methodName, Param[] skylarkParams) {
    this.methodName = methodName;
    this.skylarkParams = skylarkParams;
  }

  /**
   * @param name the name of the method to expose to skylark
   * @param method the method as a MethodHandle that we want to expose
   * @param namedParams a list of the named parameters, in order, that maps to the end of the list
   *     of parameters for the method
   * @return an instance of the annotation to expose the function to skylark
   */
  static BuckStarlarkCallable fromMethod(
      String name, MethodHandle method, List<String> namedParams) {
    Class<?>[] parameters = method.type().parameterArray();
    Param[] skylarkParams = new Param[parameters.length];
    int namedParamsIndex = namedParams.size() - 1;
    for (int i = parameters.length - 1; i >= 0; i--) {
      skylarkParams[i] =
          BuckStarlarkParam.fromParam(
              parameters[i], namedParamsIndex >= 0 ? namedParams.get(namedParamsIndex--) : null);
    }
    return new BuckStarlarkCallable(name, skylarkParams);
  }

  @Override
  public String name() {
    return methodName;
  }

  @Override
  public Param[] parameters() {
    return skylarkParams;
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return SkylarkCallable.class;
  }

  @Override
  public String doc() {
    return "";
  }

  @Override
  public boolean documented() {
    return false;
  }

  @Override
  public boolean structField() {
    return false;
  }

  @Override
  public Param extraPositionals() {
    return BuckStarlarkParam.NONE;
  }

  @Override
  public Param extraKeywords() {
    return BuckStarlarkParam.NONE;
  }

  @Override
  public boolean selfCall() {
    return false;
  }

  @Override
  public boolean allowReturnNones() {
    return false;
  }

  @Override
  public boolean useLocation() {
    return false;
  }

  @Override
  public boolean useAst() {
    return false;
  }

  @Override
  public boolean useEnvironment() {
    return false;
  }

  @Override
  public boolean useSkylarkSemantics() {
    return false;
  }

  @Override
  public boolean useContext() {
    return false;
  }

  @Override
  public StarlarkSemantics.FlagIdentifier enableOnlyWithFlag() {
    return StarlarkSemantics.FlagIdentifier.NONE;
  }

  @Override
  public StarlarkSemantics.FlagIdentifier disableWithFlag() {
    return StarlarkSemantics.FlagIdentifier.NONE;
  }
}
