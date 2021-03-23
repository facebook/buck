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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.skylark.function.packages.Info;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * A class for the Skylark native module providing functions for parsing build files. It includes
 * all functions provided natively by Buck and are available using {@code native.foo} in build file
 * extensions and just {@code foo} in build files.
 */
@StarlarkBuiltin(
    name = "native",
    doc =
        "A built-in module providing native rules and other package helper functions. "
            + "All native rules appear as functions in this module, e.g. "
            + "<code>native.cxx_library</code>.")
public class SkylarkBuildModule extends AbstractSkylarkFunctions implements SkylarkFunctionModule {

  // Matches any of "**/", "*/**/" globs
  private static final Pattern TOP_LEVEL_GLOB_PATTERN = Pattern.compile("(\\*/)*\\*\\*/.*");

  /** {@code package_name} */
  @StarlarkMethod(
      name = "package_name",
      doc =
          "The name of the package being evaluated. "
              + "For example, in the build file <code>some/package/BUCK</code>, its value "
              + "will be <code>some/package</code>. "
              + "If the BUCK file calls a function defined in a .bzl file, "
              + "<code>package_name()</code> will match the caller BUCK file package. "
              + "This function is equivalent to the deprecated variable <code>PACKAGE_NAME</code>.",
      parameters = {},
      useStarlarkThread = true)
  public String packageName(StarlarkThread env) throws EvalException {
    ParseContext parseContext = ParseContext.getParseContext(env, "package_name");
    return parseContext.getPackageContext().getBasePath().toString();
  }

  /** {@code repository_name} */
  @StarlarkMethod(
      name = "repository_name",
      doc =
          "The name of the repository the rule or build extension is called from. "
              + "For example, in packages that are called into existence inside of the cell "
              + "<code>foo</code> it will return <code>@foo</code>. In packages in the main "
              + "repository (or standalone project), it will be set to <code>@</code>.",
      parameters = {},
      useStarlarkThread = true)
  public String repositoryName(StarlarkThread env) throws EvalException {
    ParseContext parseContext = ParseContext.getParseContext(env, "repository_name");
    return "@" + parseContext.getPackageContext().getCellName();
  }

  /** {@code rule_exists} */
  @StarlarkMethod(
      name = "rule_exists",
      doc =
          "Returns True if there is a previously defined rule with provided name, "
              + "or False if the rule with such name does not exist.",
      parameters = {
        @Param(
            name = "name",
            allowedTypes = @ParamType(type = String.class),
            doc = "The name of the rule.")
      },
      useStarlarkThread = true)
  public Boolean ruleExists(String name, StarlarkThread env) throws EvalException {
    ParseContext parseContext = ParseContext.getParseContext(env, "rule_exists");
    return parseContext.hasRule(name);
  }

  /** {@code glob} */
  @StarlarkMethod(
      name = "glob",
      doc = "Returns a list of files that match glob search pattern.",
      parameters = {
        @Param(
            name = "include",
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class),
            named = true,
            doc = "a list of strings specifying patterns of files to include."),
        @Param(
            name = "exclude",
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class),
            defaultValue = "[]",
            positional = false,
            named = true,
            doc = "a list of strings specifying patterns of files to exclude."),
        @Param(
            name = "exclude_directories",
            allowedTypes = @ParamType(type = Boolean.class),
            defaultValue = "True",
            positional = false,
            named = true,
            doc = "True indicates directories should not be matched."),
      },
      documented = false,
      useStarlarkThread = true)
  public StarlarkList<String> glob(
      StarlarkList<String> include,
      StarlarkList<String> exclude,
      Boolean excludeDirectories,
      StarlarkThread env)
      throws EvalException, IOException, InterruptedException {
    ParseContext parseContext = ParseContext.getParseContext(env, "glob");
    if (include.isEmpty()) {
      return StarlarkList.empty();
    }

    boolean buildRoot =
        parseContext.getPackageContext().getCellName().equals(CanonicalCellName.rootCell())
            && parseContext.getPackageContext().getBasePath().equals(ForwardRelativePath.EMPTY);
    if (buildRoot
        && include.stream().anyMatch(str -> TOP_LEVEL_GLOB_PATTERN.matcher(str).matches())) {
      throw new EvalException("Recursive globs are prohibited at top-level directory");
    }

    try {
      return StarlarkList.copyOf(
          env.mutability(),
          parseContext.getPackageContext().getGlobber()
              .run(include.asList(), exclude.asList(), excludeDirectories).stream()
              .sorted()
              .collect(ImmutableList.toImmutableList()));
    } catch (FileNotFoundException e) {
      throw new EvalException("Cannot find " + e.getMessage());
    } catch (Exception e) {
      throw new EvalException(null, "Other exception: " + e.toString(), e);
    }
  }

  /** {@code host_info} */
  @StarlarkMethod(
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

  /** {@code implicit_package_symbol} */
  @StarlarkMethod(
      name = "implicit_package_symbol",
      doc =
          "Gives access to a symbol that has been implicitly loaded for the package of the build "
              + "file that is currently being evaluated. If the symbol was not present, "
              + "`default` will be returned.",
      documented = true,
      useStarlarkThread = true,
      allowReturnNones = true,
      parameters = {
        @Param(
            name = "symbol",
            allowedTypes = @ParamType(type = String.class),
            doc = "the symbol from implicitly loaded files to return."),
        @Param(
            name = "default",
            defaultValue = "None",
            doc = "if no implicit symbol with the requested name exists, return this value."),
      })
  public @Nullable Object implicitPackageSymbol(
      String symbol, Object defaultValue, StarlarkThread env) throws EvalException {
    return ParseContext.getParseContext(env, "implicit_package_symbol")
        .getPackageContext()
        .getImplicitlyLoadedSymbols()
        .getOrDefault(symbol, defaultValue);
  }

  public static final SkylarkBuildModule BUILD_MODULE = new SkylarkBuildModule();
}
