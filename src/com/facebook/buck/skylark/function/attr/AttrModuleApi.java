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
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;

/** Skylark module used to configure the attribute schema for user defined rules */
@SkylarkModule(
    name = "attr",
    title = "attr",
    doc = "A module which contains methods to define parameters for user defined rules",
    category = SkylarkModuleCategory.BUILTIN,
    namespace = true)
public interface AttrModuleApi extends SkylarkValue {
  @SkylarkCallable(
      name = "int",
      doc = "Create a parameter for user defined rules that is an integer",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "0",
            noneable = false,
            positional = false,
            named = true,
            type = Integer.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = AttributeConstants.VALUES_PARAM_NAME,
            doc = AttributeConstants.VALUES_PARAM_DOC,
            defaultValue = AttributeConstants.VALUES_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            generic1 = Integer.class,
            type = SkylarkList.class)
      })
  AttributeHolder intAttribute(
      Integer defaultValue, String doc, Boolean mandatory, SkylarkList<Integer> values)
      throws EvalException;

  @SkylarkCallable(
      name = "int_list",
      doc = "Create a parameter for user defined rules that is a list of ints",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            noneable = false,
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = Integer.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            type = Boolean.class)
      })
  AttributeHolder intListAttribute(
      SkylarkList<Integer> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @SkylarkCallable(
      name = "string",
      doc = "Create a parameter for user defined rules that is a string",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "''",
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = AttributeConstants.VALUES_PARAM_NAME,
            doc = AttributeConstants.VALUES_PARAM_DOC,
            defaultValue = AttributeConstants.VALUES_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            generic1 = String.class,
            type = SkylarkList.class)
      })
  AttributeHolder stringAttribute(
      String defaultValue, String doc, Boolean mandatory, SkylarkList<String> values)
      throws EvalException;

  @SkylarkCallable(
      name = "string_list",
      doc = "Create a parameter for user defined rules that is a list of strings",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            noneable = false,
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            type = Boolean.class)
      })
  AttributeHolder stringListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @SkylarkCallable(
      name = "bool",
      doc = "Create a parameter for user defined rules that is a boolean",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "False",
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
      })
  AttributeHolder boolAttribute(boolean defaultValue, String doc, boolean mandatory)
      throws EvalException;

  @SkylarkCallable(
      name = "source_list",
      doc =
          "Create a parameter for user defined rules that is a list of sources, both source files "
              + "on disk, and build targets.\nThis is exposed to rule implementations via "
              + "ctx.attr.{@code name}  as a list of artifacts, not as a list of ProviderInfo "
              + "objects.\nIf only a list of dependencies is required, or ProviderInfo objects "
              + "are needed, this is not the correct attribute to use.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            noneable = false,
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "allow_empty",
            doc = "Whether the source list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            type = Boolean.class)
      })
  AttributeHolder sourceListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;

  @SkylarkCallable(
      name = "source",
      doc =
          "Create a parameter for user defined rules that is a source file on disk "
              + "or a build target.\nThis is exposed to rule implementations via "
              + "ctx.attr.{@code name}  as a single artifact, not as a ProviderInfoCollection "
              + "object.\nIf only a dependency is required, or ProviderInfo objects "
              + "are needed, this is not the correct attribute to use.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "None",
            noneable = true,
            positional = false,
            named = true,
            type = String.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc =
                "The default value for this parameter. Note that `None` is not valid and is "
                    + "only used as there is no universal default value that is applicable.",
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class)
      })
  AttributeHolder sourceAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException;

  @SkylarkCallable(
      name = "dep",
      doc =
          "Create a parameter for user defined rules that is a build target.\nThis is exposed to "
              + "rule implementations via ctx.attr.{@code name} as a single ProviderInfoCollection "
              + "object.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "None",
            noneable = true,
            positional = false,
            named = true,
            type = String.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc =
                "The default value for this parameter. Note that `None` is not valid and is "
                    + "only used as there is no universal default value that is applicable.",
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "providers",
            doc = "If non-empty, all specified build targets must have these providers",
            defaultValue = "[]",
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = Provider.class)
      })
  AttributeHolder depAttribute(
      Object defaultValue, String doc, boolean mandatory, SkylarkList<Provider<?>> providers)
      throws EvalException;

  @SkylarkCallable(
      name = "dep_list",
      doc =
          "Create a parameter for user defined rules that is a list of build targets that are "
              + "dependencies of this target\nThis is exposed to rule implementations via "
              + "ctx.attr.{@code name} as a list of ProviderInfoCollection objects, that provide "
              + "access to various metadata about the dependency.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            noneable = false,
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "allow_empty",
            doc = "Whether the dep list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "providers",
            doc = "If non-empty, all specified build targets must have these providers",
            defaultValue = "[]",
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = Provider.class)
      })
  AttributeHolder depListAttribute(
      SkylarkList<String> defaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty,
      SkylarkList<Provider<?>> providers)
      throws EvalException;

  @SkylarkCallable(
      name = "output",
      doc =
          "Create a parameter for user defined rules that is an output artifact.\n"
              + "Rules with this attribute take a string file name, and an artifact is "
              + "automatically declared with this name (see ctx.actions.declare_file). This is "
              + "exposed to rule implementations via ctx.attr.{@code name} as a single Artifact "
              + "object. Note that if `None` is set for the default value, a value /must/ be "
              + "provided at runtime.",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "None",
            noneable = true,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = "True",
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
      },
      useLocation = true)
  AttributeHolder outputAttribute(
      Object defaultValue, String doc, boolean mandatory, Location location) throws EvalException;

  @SkylarkCallable(
      name = "output_list",
      doc =
          "Create a parameter for user defined rules that is a list of output artifacts.\n"
              + "Rules with this attribute take a list of string file names, and an artifact is "
              + "automatically declared for each name name (see ctx.actions.declare_file). This is "
              + "exposed to rule implementations via ctx.attr.{@code name} as a list of Artifact "
              + "objects",
      parameters = {
        @Param(
            name = AttributeConstants.DEFAULT_PARAM_NAME,
            doc = AttributeConstants.DEFAULT_PARAM_DOC,
            defaultValue = "[]",
            noneable = false,
            positional = false,
            named = true,
            type = SkylarkList.class,
            generic1 = String.class),
        @Param(
            name = AttributeConstants.DOC_PARAM_NAME,
            doc = AttributeConstants.DOC_PARAM_DOC,
            defaultValue = AttributeConstants.DOC_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = String.class),
        @Param(
            name = AttributeConstants.MANDATORY_PARAM_NAME,
            doc = AttributeConstants.MANDATORY_PARAM_DOC,
            defaultValue = AttributeConstants.MANDATORY_PARAM_DEFAULT_VALUE,
            noneable = false,
            positional = false,
            named = true,
            type = Boolean.class),
        @Param(
            name = "allow_empty",
            doc = "Whether the list may be empty",
            defaultValue = "False",
            positional = false,
            named = true,
            type = Boolean.class),
      })
  AttributeHolder outputListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException;
}
