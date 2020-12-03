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
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkDocumentationCategory;
import net.starlark.java.annot.StarlarkMethod;

/**
 * A class for the Skylark native module providing functions for parsing build files. It includes
 * all functions provided natively by Buck and are available using {@code native.foo} in build file
 * extensions and just {@code foo} in build files.
 */
@StarlarkBuiltin(
    name = "native",
    category = StarlarkDocumentationCategory.BUILTIN,
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
      parameters = {@Param(name = "name", type = String.class, doc = "The name of the rule.")},
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
            type = StarlarkList.class,
            generic1 = String.class,
            named = true,
            doc = "a list of strings specifying patterns of files to include."),
        @Param(
            name = "exclude",
            type = StarlarkList.class,
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
          parseContext.getPackageContext().getGlobber().run(include, exclude, excludeDirectories)
              .stream()
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
            type = String.class,
            doc = "the symbol from implicitly loaded files to return."),
        @Param(
            name = "default",
            type = Object.class,
            defaultValue = "None",
            noneable = true,
            doc = "if no implicit symbol with the requested name exists, return this value."),
      })
  public @Nullable Object implicitPackageSymbol(
      String symbol, Object defaultValue, StarlarkThread env) throws EvalException {
    return ParseContext.getParseContext(env, "implicit_package_symbol")
        .getPackageContext()
        .getImplicitlyLoadedSymbols()
        .getOrDefault(symbol, defaultValue);
  }

  /** {@code depset} */
  @StarlarkMethod(
      name = "depset",
      doc =
          "Creates a <a href=\"depset.html\">depset</a>. The <code>direct</code> parameter is a "
              + "list of direct elements of the depset, and <code>transitive</code> parameter is "
              + "a list of depsets whose elements become indirect elements of the created depset. "
              + "The order in which elements are returned when the depset is converted to a list "
              + "is specified by the <code>order</code> parameter. "
              + "See the <a href=\"../depsets.md\">Depsets overview</a> for more information. "
              + "<p>All elements (direct and indirect) of a depset must be of the same type, "
              + "as obtained by the expression <code>type(x)</code>."
              + "<p>Because a hash-based set is used to eliminate duplicates during iteration, "
              + "all elements of a depset should be hashable. However, this invariant is not "
              + "currently checked consistently in all constructors. Use the "
              + "--incompatible_always_check_depset_elements flag to enable "
              + "consistent checking; this will be the default behavior in future releases; "
              + " see <a href='https://github.com/bazelbuild/bazel/issues/10313'>Issue 10313</a>."
              + "<p>In addition, elements must currently be immutable, though this restriction "
              + "will be relaxed in future."
              + "<p> The order of the created depset should be <i>compatible</i> with the order of "
              + "its <code>transitive</code> depsets. <code>\"default\"</code> order is compatible "
              + "with any other order, all other orders are only compatible with themselves."
              + "<p> Note on backward/forward compatibility. This function currently accepts a "
              + "positional <code>items</code> parameter. It is deprecated and will be removed "
              + "in the future, and after its removal <code>direct</code> will become a sole "
              + "positional parameter of the <code>depset</code> function. Thus, both of the "
              + "following calls are equivalent and future-proof:<br>"
              + "<pre class=language-python>"
              + "depset(['a', 'b'], transitive = [...])\n"
              + "depset(direct = ['a', 'b'], transitive = [...])\n"
              + "</pre>",
      parameters = {
        @Param(
            name = "x",
            type = Object.class,
            defaultValue = "None",
            positional = true,
            named = false,
            noneable = true,
            doc =
                "A positional parameter distinct from other parameters for legacy support. "
                    + "<p>If <code>--incompatible_disable_depset_inputs</code> is false, this "
                    + "parameter serves as the value of <code>items</code>.</p> "
                    + "<p>If <code>--incompatible_disable_depset_inputs</code> is true, this "
                    + "parameter serves as the value of <code>direct</code>.</p> "
                    + "<p>See the documentation for these parameters for more details."),
        // TODO(cparsons): Make 'order' keyword-only.
        @Param(
            name = "order",
            type = String.class,
            defaultValue = "\"default\"",
            doc =
                "The traversal strategy for the new depset. See "
                    + "<a href=\"depset.html\">here</a> for the possible values.",
            named = true),
        @Param(
            name = "direct",
            type = Object.class,
            defaultValue = "None",
            positional = false,
            named = true,
            noneable = true,
            doc = "A list of <i>direct</i> elements of a depset. "),
        @Param(
            name = "transitive",
            named = true,
            positional = false,
            type = Sequence.class,
            generic1 = Depset.class,
            noneable = true,
            doc = "A list of depsets whose elements will become indirect elements of the depset.",
            defaultValue = "None"),
      },
      useStarlarkThread = true)
  public Depset depset(
      Object x, String orderString, Object direct, Object transitive, StarlarkThread thread)
      throws EvalException {
    Order order;
    try {
      order = Order.parse(orderString);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(null, ex);
    }

    if (x != Starlark.NONE) {
      if (direct != Starlark.NONE) {
        throw new EvalException(
            null, "parameter 'direct' cannot be specified both positionally and by keyword");
      }
      direct = x;
    }
    if (direct instanceof Depset) {
      throw new EvalException(
          null,
          "parameter 'direct' must contain a list of elements, and may no longer accept a"
              + " depset. The deprecated behavior may be temporarily re-enabled by setting"
              + " --incompatible_disable_depset_inputs=false");
    }
    return Depset.fromDirectAndTransitive(
        order,
        Sequence.noneableCast(direct, Object.class, "direct"),
        Sequence.noneableCast(transitive, Depset.class, "transitive"),
        thread.getSemantics().incompatibleAlwaysCheckDepsetElements());
  }

  public static final SkylarkBuildModule BUILD_MODULE = new SkylarkBuildModule();
}
