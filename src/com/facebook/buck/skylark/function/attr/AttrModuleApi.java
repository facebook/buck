/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.skylark.function.attr;

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
}
