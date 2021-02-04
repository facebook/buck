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

package com.facebook.buck.skylark.function;

import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * A class for the Skylark native module providing functions for parsing package files. It provides
 * package() and other shared functions that are available also using {@code native.foo} in build
 * file extensions and just {@code foo} in package files.
 */
@StarlarkBuiltin(
    name = "native",
    doc =
        "A built-in module providing native rules and other package helper functions. "
            + "All native rules appear as functions in this module, e.g. "
            + "<code>native.package</code>.")
public class SkylarkPackageModule extends AbstractSkylarkFunctions
    implements SkylarkFunctionModule {

  /** {@code package} */
  @StarlarkMethod(
      name = "package",
      doc = "Allows defining attributes applied to all targets in the build file.",
      documented = true,
      useStarlarkThread = true,
      allowReturnNones = true,
      parameters = {
        @Param(
            name = "inherit",
            allowedTypes = @ParamType(type = Boolean.class),
            defaultValue = "False",
            positional = false,
            named = true,
            doc = "whether to inherit properties from the parent package."),
        @Param(
            name = CommonParamNames.VISIBILITY_NAME,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class),
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "a list of build patterns to make targets visible to."),
        @Param(
            name = CommonParamNames.WITHIN_VIEW_NAME,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class),
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "a list of build patterns that targets may depend on."),
      })
  public void packageFunction(
      Boolean inherit,
      StarlarkList<String> visibility,
      StarlarkList<String> within_view,
      StarlarkThread env)
      throws EvalException {
    ParseContext.getParseContext(env, "package")
        .recordPackage(
            PackageMetadata.of(
                inherit, ImmutableList.copyOf(visibility), ImmutableList.copyOf(within_view)));
  }

  public static final SkylarkPackageModule PACKAGE_MODULE = new SkylarkPackageModule();
}
