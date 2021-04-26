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

package com.facebook.buck.skylark.function.attr;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

/** Skylark module used to configure the attribute schema for user defined rules */
@StarlarkBuiltin(
    name = "attr",
    doc = "A module which contains methods to define parameters for user defined rules")
public interface AttrModuleApi {
  @StarlarkMethod(
      name = "int",
      doc = "Specifies a parameter for a User Defined Rule that is an integer.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "0",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkInt.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = AttributeConstants.VALUES_PARAM_NAME,
            doc = AttributeConstants.VALUES_PARAM_DOC,
            defaultValue = AttributeConstants.VALUES_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = StarlarkInt.class))
      })
  AttributeHolder intAttribute(
      StarlarkInt defaultValue, String doc, Boolean mandatory, StarlarkList<?> values)
      throws EvalException;

  @StarlarkMethod(
      name = "int_list",
      doc = "Specifies a parameter for a User Defined Rule that is a list of integers.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = Integer.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class))
      })
  AttributeHolder intListAttribute(
      StarlarkList<?> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @StarlarkMethod(
      name = "string",
      doc = "Specifies a parameter for a User Defined Rule that is a string.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "''",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = AttributeConstants.VALUES_PARAM_NAME,
            doc = AttributeConstants.VALUES_PARAM_DOC,
            defaultValue = AttributeConstants.VALUES_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class))
      })
  AttributeHolder stringAttribute(
      String defaultValue, String doc, Boolean mandatory, StarlarkList<?> values)
      throws EvalException;

  @StarlarkMethod(
      name = "string_list",
      doc = "Specifies a parameter for a User Defined Rule that is a list of strings.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class))
      })
  AttributeHolder stringListAttribute(
      StarlarkList<?> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @StarlarkMethod(
      name = "bool",
      doc = "Specifies a parameter for a User Defined Rule that is a boolean.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
      })
  AttributeHolder boolAttribute(boolean defaultValue, String doc, boolean mandatory)
      throws EvalException;

  @StarlarkMethod(
      name = "source",
      doc =
          "Specifies a parameter for User Defined Rules that is a source."
              + "A 'source' can be a file in the repo or a build target. If a build target is "
              + "specified, the source is taken from its `DefaultInfo` provider based on the "
              + "output label. The output label must return exactly one `Artifact`. If it "
              + "specifies zero or more than one artifacts, the build will fail. This is exposed "
              + "to rule implementations via ctx.attr.`name` as a single `Artifact`, not as a "
              + "`Dependency`. If a dependency is required, or access to providers are required, "
              + "this is not the correct attribute to use. See `attr.dep()`",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC_NO_NONE,
            defaultValue = "None",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)}),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class))
      })
  AttributeHolder sourceAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException;

  @StarlarkMethod(
      name = "source_list",
      doc =
          "Specifies a parameter for User Defined Rules that is a list of sources. These can be "
              + "both source files in the repo, and build targets. Sources for build targets are "
              + "taken from the target's `DefaultInfo` provider\n"
              + "This is exposed to rule implementations via ctx.attr.`name` as a list of "
              + "`Artifact` objects, not as a list of `Dependency` objects.\n"
              + "If a list of dependencies is required, or access to providers is required, "
              + "this is not the correct attribute to use. See instead `attr.dep_list()`",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "allow_empty",
            doc = "Whether the source list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class))
      })
  AttributeHolder sourceListAttribute(
      StarlarkList<?> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @StarlarkMethod(
      name = "dep",
      doc =
          "Specifies a parameter for User Defined Rules that is a dependency's build target.\n"
              + "This is exposed to rule implementations via ctx.attr.`name` as a single "
              + "`Dependency` object.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC_NO_NONE,
            defaultValue = "None",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)}),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "providers",
            doc =
                "If non-empty, the specified build target must return these providers. e.g. "
                    + "To require a dependency to be runnable, specify `[RunInfo]`.",
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = Provider.class))
      })
  AttributeHolder depAttribute(
      Object defaultValue, String doc, boolean mandatory, StarlarkList<?> providers)
      throws EvalException;

  @StarlarkMethod(
      name = "dep_list",
      doc =
          "Specifies a parameter for User Defined Rules that is a list of build targets\n"
              + "This is exposed to rule implementations via ctx.attr.`name` as a list of "
              + "`Dependency` objects",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "allow_empty",
            doc = "Whether the dep list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "providers",
            doc =
                "If non-empty, all specified build targets must return these providers. e.g. "
                    + "To require all dependencies to be runnable, specify `[RunInfo]`.",
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = Provider.class))
      })
  AttributeHolder depListAttribute(
      StarlarkList<?> defaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty,
      StarlarkList<?> providers)
      throws EvalException;

  @StarlarkMethod(
      name = "output",
      doc =
          "Specifies a parameter for User Defined Rules that is an output `Artifact`.\n"
              + "Rules with this attribute take a string file name, and an artifact is "
              + "automatically declared with this name (see `ctx.actions.declare_file()`). This is "
              + "exposed to rule implementations via ctx.attr.`name` as a single `Artifact` "
              + "object.\n"
              + "Note that this output *must* be used as the output of an action. The build will "
              + "fail otherwise.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC_NO_NONE,
            defaultValue = "None",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = String.class)}),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = "True",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
      })
  AttributeHolder outputAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException;

  @StarlarkMethod(
      name = "output_list",
      doc =
          "Specifies a parameter for User Defined Rules that is a list of output `Artifact`s.\n"
              + "Rules with this attribute take a list of string file names, and an artifact is "
              + "automatically declared for each name name (see `ctx.actions.declare_file()`). "
              + "This is exposed to rule implementations via ctx.attr.`name` as a list of "
              + "`Artifact` objects"
              + "Note that the outputs *must* be used as the output of an action. The build will "
              + "fail otherwise.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class, generic1 = String.class)),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = String.class)),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            allowedTypes = @ParamType(type = Boolean.class)),
      })
  AttributeHolder outputListAttribute(
      StarlarkList<?> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;
}
