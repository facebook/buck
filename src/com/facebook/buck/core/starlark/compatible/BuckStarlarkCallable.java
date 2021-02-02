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

import com.google.devtools.build.lib.syntax.Location;
import java.lang.invoke.MethodHandle;
import java.util.List;

/**
 * An instance of the skylark annotation that we create and pass around to piggy-back off skylark
 * functions.
 */
@SuppressWarnings("all")
class BuckStarlarkCallable {

  private final String methodName;
  private final BuckStarlarkParam[] skylarkParams;

  private BuckStarlarkCallable(String methodName, BuckStarlarkParam[] skylarkParams) {
    this.methodName = methodName;
    this.skylarkParams = skylarkParams;
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
   * @return an instance of the annotation to expose the function to skylark
   */
  static BuckStarlarkCallable fromMethod(
      String name,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues) {
    Class<?>[] parameters = method.type().parameterArray();
    // Use class equality here because otherwise a last argument of 'Object' could get confused
    // for a location
    if (parameters.length > 0 && parameters[parameters.length - 1] == Location.class) {
      throw new IllegalStateException("Location parameter is no longer supported: " + method);
    }

    BuckStarlarkParam[] skylarkParams = new BuckStarlarkParam[parameters.length];

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
      skylarkParams[i] = BuckStarlarkParam.fromParam(parameters[i], namedParam, defaultValue);
    }

    return new BuckStarlarkCallable(name, skylarkParams);
  }

  public String name() {
    return methodName;
  }

  public BuckStarlarkParam[] parameters() {
    return skylarkParams;
  }
}
