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

import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;

/**
 * A class for the Skylark native module. It includes all functions provided natively by Buck and
 * are available using {@code native.foo} in build file extensions and just {@code foo} in build
 * files.
 */
@SkylarkModule(
  name = "native",
  namespace = true,
  category = SkylarkModuleCategory.BUILTIN,
  doc =
      "A built-in module providing native rules and other package helper functions. "
          + "All native rules appear as functions in this module, e.g. "
          + "<code>native.cxx_library</code>."
)
public class SkylarkNativeModule {

  @SkylarkSignature(
    name = "package_name",
    objectType = SkylarkNativeModule.class,
    returnType = String.class,
    doc =
        "The name of the package being evaluated. "
            + "For example, in the build file <code>some/package/BUCK</code>, its value "
            + "will be <code>some/package</code>. "
            + "If the BUCK file calls a function defined in a .bzl file, "
            + "<code>package_name()</code> will match the caller BUCK file package. "
            + "This function is equivalent to the deprecated variable <code>PACKAGE_NAME</code>.",
    parameters = {},
    useAst = true,
    useEnvironment = true
  )
  public static final BuiltinFunction packageName =
      new BuiltinFunction("package_name") {
        @SuppressWarnings("unused")
        public String invoke(FuncallExpression ast, Environment env) throws EvalException {
          env.checkLoadingPhase("native.package_name", ast.getLocation());
          return (String) env.lookup("PACKAGE_NAME");
        }
      };

  public static final SkylarkNativeModule NATIVE_MODULE = new SkylarkNativeModule();

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(SkylarkNativeModule.class);
  }
}
