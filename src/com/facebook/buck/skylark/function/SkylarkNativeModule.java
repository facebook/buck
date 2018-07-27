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

import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.Type;
import java.io.FileNotFoundException;
import java.io.IOException;

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
            + "<code>native.cxx_library</code>.")
public class SkylarkNativeModule {

  @SkylarkCallable(
      name = "package_name",
      doc =
          "The name of the package being evaluated. "
              + "For example, in the build file <code>some/package/BUCK</code>, its value "
              + "will be <code>some/package</code>. "
              + "If the BUCK file calls a function defined in a .bzl file, "
              + "<code>package_name()</code> will match the caller BUCK file package. "
              + "This function is equivalent to the deprecated variable <code>PACKAGE_NAME</code>.",
      parameters = {},
      useAst = true,
      useEnvironment = true)
  public String packageName(FuncallExpression ast, Environment env) throws EvalException {
    env.checkLoadingPhase("native.package_name", ast.getLocation());
    ParseContext parseContext = ParseContext.getParseContext(env, ast);
    return parseContext
        .getPackageContext()
        .getPackageIdentifier()
        .getPackageFragment()
        .getPathString();
  }

  @SkylarkCallable(
      name = "repository_name",
      doc =
          "The name of the repository the rule or build extension is called from. "
              + "For example, in packages that are called into existence inside of the cell "
              + "<code>foo</code> it will return <code>@foo</code>. In packages in the main "
              + "repository (or standalone project), it will be set to <code>@</code>.",
      parameters = {},
      useLocation = true,
      useAst = true,
      useEnvironment = true)
  public String repositoryName(Location location, FuncallExpression ast, Environment env)
      throws EvalException {
    env.checkLoadingPhase("native.repository_name", location);
    ParseContext parseContext = ParseContext.getParseContext(env, ast);
    return parseContext.getPackageContext().getPackageIdentifier().getRepository().getName();
  }

  @SkylarkCallable(
      name = "rule_exists",
      doc =
          "Returns True if there is a previously defined rule with provided name, "
              + "or False if the rule with such name does not exist.",
      parameters = {@Param(name = "name", type = String.class, doc = "The name of the rule.")},
      useAst = true,
      useEnvironment = true)
  public Boolean ruleExists(String name, FuncallExpression ast, Environment env)
      throws EvalException {
    env.checkLoadingOrWorkspacePhase("native.rule_exists", ast.getLocation());
    ParseContext parseContext = ParseContext.getParseContext(env, ast);
    return parseContext.hasRule(name);
  }

  @SkylarkCallable(
      name = "glob",
      doc = "Returns a list of files that match glob search pattern.",
      parameters = {
        @Param(
            name = "include",
            type = SkylarkList.class,
            generic1 = String.class,
            doc = "a list of strings specifying patterns of files to include."),
        @Param(
            name = "exclude",
            type = SkylarkList.class,
            generic1 = String.class,
            defaultValue = "[]",
            positional = false,
            named = true,
            doc = "a list of strings specifying patterns of files to exclude."),
        @Param(
            name = "exclude_directories",
            type = Boolean.class,
            defaultValue = "True",
            positional = false,
            named = true,
            doc = "True indicates directories should not be matched."),
      },
      documented = false,
      useAst = true,
      useEnvironment = true)
  public SkylarkList<String> glob(
      SkylarkList<String> include,
      SkylarkList<String> exclude,
      Boolean excludeDirectories,
      FuncallExpression ast,
      Environment env)
      throws EvalException, IOException, InterruptedException {
    ParseContext parseContext = ParseContext.getParseContext(env, ast);
    if (include.isEmpty()) {
      parseContext
          .getPackageContext()
          .getEventHandler()
          .handle(
              Event.warn(
                  ast.getLocation(),
                  "glob's 'include' attribute is empty. "
                      + "Such calls are expensive and unnecessary. "
                      + "Please use an empty list ([]) instead."));
      return SkylarkList.MutableList.empty();
    }
    if (parseContext.getPackageContext().getPackageIdentifier().getRunfilesPath().isEmpty()
        && include.stream().anyMatch(str -> str.matches("(\\*\\/)*\\*\\*\\/.*"))) {
      // Matches any of "**/", "*/**/" globs
      throw new EvalException(
          ast.getLocation(), "Recursive globs are prohibited at top-level directory");
    }

    try {
      return SkylarkList.MutableList.copyOf(
          env,
          parseContext
              .getPackageContext()
              .getGlobber()
              .run(
                  Type.STRING_LIST.convert(include, "'glob' include"),
                  Type.STRING_LIST.convert(exclude, "'glob' exclude"),
                  excludeDirectories)
              .stream()
              .sorted()
              .collect(ImmutableList.toImmutableList()));
    } catch (FileNotFoundException e) {
      throw new EvalException(ast.getLocation(), "Cannot find " + e.getMessage());
    }
  }

  public static final SkylarkNativeModule NATIVE_MODULE = new SkylarkNativeModule();
}
