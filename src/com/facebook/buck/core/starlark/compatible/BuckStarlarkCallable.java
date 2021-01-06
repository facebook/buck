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

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * An instance of the skylark annotation that we create and pass around to piggy-back off skylark
 * functions.
 */
@SuppressWarnings("all")
class BuckStarlarkCallable implements SkylarkCallable {

  private final String methodName;
  private final Param[] skylarkParams;
  private final boolean useLocation;

  private BuckStarlarkCallable(String methodName, Param[] skylarkParams, boolean useLocation) {
    this.methodName = methodName;
    this.skylarkParams = skylarkParams;
    this.useLocation = useLocation;
  }

  /**
   * @param name the name of the method to expose to skylark
   * @param method the method as a MethodHandle that we want to expose
   * @param namedParams a list of the named parameters, in order, that maps to the end of the list
   *     of parameters for the method. Any additional parameters of the given method will be
   *     considered mandatory parameters, starting at the beginning of the method.
   * @param defaultSkylarkValues a list of default values for each of the parameters. This will be
   *     interpreted by the skylark framework, and should correspond to {@link
   *     BuckStarlarkParam#defaultValue()}. This will be mapped to the end of the list of parameters
   *     of the method. Any additional parameters of the given method will have no defaults.
   * @param noneableParams named parameters that are allowed to be `None`
   * @return an instance of the annotation to expose the function to skylark
   */
  static BuckStarlarkCallable fromMethod(
      String name,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    Class<?>[] parameters = method.type().parameterArray();
    boolean useLocation = false;
    // Use class equality here because otherwise a last argument of 'Object' could get confused
    // for a location
    if (parameters.length > 0 && parameters[parameters.length - 1] == Location.class) {
      useLocation = true;
      parameters = Arrays.copyOf(parameters, parameters.length - 1);
    }

    Param[] skylarkParams = new Param[parameters.length];

    if (namedParams.size() > parameters.length) {
      throw new IllegalArgumentException(
          String.format(
              "The supplied list of named parameters (size %s) is larger than the number of parameters of the method size (%s).",
              namedParams.size(), parameters.length));
    }
    int namedParamsIndex = namedParams.size() - 1;

    if (defaultSkylarkValues.size() > parameters.length) {
      throw new IllegalArgumentException(
          String.format(
              "The supplied list of default parameters (size %s) is larger than the number of parameters of the method size (%s).",
              defaultSkylarkValues.size(), parameters.length));
    }
    int defaultValuesIndex = defaultSkylarkValues.size() - 1;

    for (int i = parameters.length - 1; i >= 0; i--) {
      String namedParam = namedParamsIndex >= 0 ? namedParams.get(namedParamsIndex--) : null;
      String defaultValue =
          defaultValuesIndex >= 0 ? defaultSkylarkValues.get(defaultValuesIndex--) : null;
      // TODO(pjameson): T60486516, ideally we can infer this by seeing an Optional/SkylarkOptional
      boolean noneable = namedParam != null && noneableParams.contains(namedParam);
      skylarkParams[i] =
          BuckStarlarkParam.fromParam(parameters[i], namedParam, defaultValue, noneable);
    }

    return new BuckStarlarkCallable(name, skylarkParams, useLocation);
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
    return useLocation;
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
  public boolean useStarlarkSemantics() {
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
