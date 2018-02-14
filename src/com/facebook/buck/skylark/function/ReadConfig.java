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

package com.facebook.buck.skylark.function;

import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.packages.PackageFactory;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import javax.annotation.Nullable;

/**
 * Exposes a {@code read_config} for Skylark parser.
 *
 * <p>This is a temporary solution to simplify migration from Python DSL to Skylark and allows
 * clients to query values from {@code .buckconfig} files and {@code --config} command line
 * arguments.
 *
 * <p>Example, when buck is invoked with {@code --config user.value=my_value} an invocation of
 * {@code read_config("user", "value", "default_value")} will return {@code my_value}.
 */
public class ReadConfig {

  private static final String FUNCTION_NAME = "read_config";

  /** Prevent instantiations */
  private ReadConfig() {}

  @SkylarkSignature(
    name = FUNCTION_NAME,
    objectType = Object.class,
    returnType = Object.class,
    doc = "Returns a list of files that match glob search pattern.",
    parameters = {
      @Param(
        name = "section",
        type = String.class,
        doc = "the name of the .buckconfig section with the desired value."
      ),
      @Param(
        name = "field",
        type = String.class,
        doc = "the name of the .buckconfig field with the desired value."
      ),
      @Param(
        name = "defaultValue",
        noneable = true,
        type = String.class,
        defaultValue = "None",
        doc = "the value to return if the desired value is not set in the .buckconfig."
      ),
    },
    documented = false, // this is an API that we should remove once select is available
    useAst = true,
    useEnvironment = true
  )
  private static final BuiltinFunction readConfig =
      new BuiltinFunction(FUNCTION_NAME) {
        @SuppressWarnings("unused")
        public Object invoke(
            String section,
            String field,
            Object defaultValue,
            FuncallExpression ast,
            Environment env)
            throws EvalException {
          PackageContext packageContext = PackageFactory.getPackageContext(env, ast);
          @Nullable
          String value =
              packageContext.getRawConfig().getOrDefault(section, ImmutableMap.of()).get(field);
          return value != null ? value : defaultValue;
        }
      };

  public static BuiltinFunction create() {
    return readConfig;
  }

  // configure read_config function using annotations on this class
  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(ReadConfig.class);
  }
}
