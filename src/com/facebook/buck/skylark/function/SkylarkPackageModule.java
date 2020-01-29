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
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkList;

/**
 * A class for the Skylark native module providing functions for parsing package files. It provides
 * package() and other shared functions that are available also using {@code native.foo} in build
 * file extensions and just {@code foo} in package files.
 */
@SkylarkModule(
    name = "native",
    namespace = true,
    category = SkylarkModuleCategory.BUILTIN,
    doc =
        "A built-in module providing native rules and other package helper functions. "
            + "All native rules appear as functions in this module, e.g. "
            + "<code>native.package</code>.")
public class SkylarkPackageModule extends AbstractSkylarkFunctions
    implements SkylarkFunctionModule {

  @SkylarkCallable(
      name = "package",
      doc = "Allows defining attributes applied to all targets in the build file.",
      documented = true,
      useAst = true,
      useEnvironment = true,
      allowReturnNones = true,
      parameters = {
        @Param(
            name = VisibilityAttributes.VISIBILITY,
            type = SkylarkList.class,
            generic1 = String.class,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "a list of build patterns to make targets visible to."),
        @Param(
            name = VisibilityAttributes.WITHIN_VIEW,
            type = SkylarkList.class,
            generic1 = String.class,
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "a list of build patterns that targets may depend on."),
      })
  public void packageFunction(
      SkylarkList<String> visibility,
      SkylarkList<String> within_view,
      FuncallExpression ast,
      Environment env)
      throws EvalException {
    ParseContext.getParseContext(env, ast)
        .recordPackage(
            PackageMetadata.of(ImmutableList.copyOf(visibility), ImmutableList.copyOf(within_view)),
            ast);
  }

  public static final SkylarkPackageModule PACKAGE_MODULE = new SkylarkPackageModule();
}
