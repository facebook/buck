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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.Type;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.annotation.Nullable;

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
    SkylarkUtils.checkLoadingPhase(env, "native.package_name", ast.getLocation());
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
    SkylarkUtils.checkLoadingPhase(env, "native.repository_name", location);
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
    SkylarkUtils.checkLoadingOrWorkspacePhase(env, "native.rule_exists", ast.getLocation());
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
  @SkylarkCallable(
      name = "read_config",
      doc =
          "Returns a configuration value of <code>.buckconfig</code> or <code>--config</code> flag."
              + " For example, <code>read_config('foo', 'bar', 'baz')</code> returns"
              + " <code>bazz</code> if Buck is invoked with <code>--config foo.bar=bazz</code> flag.",
      parameters = {
        @Param(
            name = "section",
            type = String.class,
            doc = "the name of the .buckconfig section with the desired value."),
        @Param(
            name = "field",
            type = String.class,
            doc = "the name of the .buckconfig field with the desired value."),
        @Param(
            name = "defaultValue",
            noneable = true,
            type = String.class,
            defaultValue = "None",
            doc = "the value to return if the desired value is not set in the .buckconfig."),
      },
      documented = false, // this is an API that we should remove once select is available
      allowReturnNones = true,
      useAst = true,
      useEnvironment = true)
  public Object readConfig(
      String section, String field, Object defaultValue, FuncallExpression ast, Environment env)
      throws EvalException {
    ParseContext parseContext = ParseContext.getParseContext(env, ast);
    @Nullable
    String value =
        parseContext
            .getPackageContext()
            .getRawConfig()
            .getOrDefault(section, ImmutableMap.of())
            .get(field);

    parseContext.recordReadConfigurationOption(section, field, value);
    return value != null ? value : defaultValue;
  }

  @SkylarkCallable(
      name = "host_info",
      doc =
          "The host_info() function is used to get processor and OS information about the host machine\n"
              + "The <code>host_info()</code> function is used to get the current OS and processor "
              + "architecture on the host. This will likely change as better cross compilation tooling "
              + "comes to Buck.\n"
              + "    <pre class=\"prettyprint lang-py\">\n"
              + "  struct(\n"
              + "      os=struct(\n"
              + "          is_linux=True|False,\n"
              + "          is_macos=True|False,\n"
              + "          is_windows=True|False,\n"
              + "          is_freebsd=True|False,\n"
              + "          is_unknown=True|False,\n"
              + "      ),\n"
              + "      arch=struct(\n"
              + "          is_aarch64=True|False,\n"
              + "          is_arm=True|False,\n"
              + "          is_armeb=True|False,\n"
              + "          is_i386=True|False,\n"
              + "          is_mips=True|False,\n"
              + "          is_mips64=True|False,\n"
              + "          is_mipsel=True|False,\n"
              + "          is_mipsel64=True|False,\n"
              + "          is_powerpc=True|False,\n"
              + "          is_ppc64=True|False,\n"
              + "          is_unknown=True|False,\n"
              + "          is_x86_64=True|False,\n"
              + "      ),\n"
              + "  )</pre>\n",
      documented = true)
  public Info hostInfo() {
    return HostInfo.HOST_INFO;
  }

  public static final SkylarkNativeModule NATIVE_MODULE = new SkylarkNativeModule();
}
