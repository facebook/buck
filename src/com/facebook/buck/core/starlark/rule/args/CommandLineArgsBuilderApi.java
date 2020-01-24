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

package com.facebook.buck.core.starlark.rule.args;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsApi;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;

/**
 * Struct for creating more efficient and validated lists of arguments to pass to actions' command
 * lines. Exposed via ctx.actions.args()
 */
@SkylarkModule(
    name = "args",
    doc = "Struct for creating lists of arguments to use in command lines of actions")
public interface CommandLineArgsBuilderApi extends SkylarkValue {
  @SkylarkCallable(
      name = "add",
      doc = "Add an argument to the existing Args object",
      parameters = {
        @Param(
            name = "arg_name_or_value",
            doc =
                "If two positional arguments are provided to `add`, this is the name of the "
                    + "argument. e.g. `--foo` in `--foo bar`. If only one positional argument is "
                    + "provided, this is the value of the argument",
            named = false,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Integer.class),
              @ParamType(type = Artifact.class),
              @ParamType(type = Label.class),
              @ParamType(type = OutputArtifact.class),
              @ParamType(type = CommandLineArgsApi.class)
            }),
        @Param(
            name = "value",
            defaultValue = "unbound",
            doc = "If provided, the value",
            named = false,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Integer.class),
              @ParamType(type = Artifact.class),
              @ParamType(type = Label.class),
              @ParamType(type = OutputArtifact.class),
              @ParamType(type = CommandLineArgsApi.class)
            }),
        @Param(
            name = "format",
            doc =
                "A format string to apply after stringifying each argument. This must contain one "
                    + "or more %s. Each will be replaced with the string value of each argument "
                    + "at execution time.",
            type = String.class,
            named = true,
            positional = false,
            defaultValue = "\"%s\"")
      },
      useLocation = true)
  CommandLineArgsBuilderApi add(
      Object argNameOrValue, Object value, String formatString, Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "add_all",
      doc = "Add an argument to the existing Args object",
      parameters = {
        @Param(
            name = "values",
            doc =
                "Values to add to the existing Args object. Must be one of str, int, Label, "
                    + "Artifact or RunInfo",
            type = SkylarkList.class),
        @Param(
            name = "format",
            doc =
                "A format string to apply after stringifying each argument. This must contain one "
                    + "or more %s. Each will be replaced with the string value of each argument "
                    + "at execution time.",
            type = String.class,
            named = true,
            defaultValue = "\"%s\"")
      },
      useLocation = true)
  CommandLineArgsBuilderApi addAll(SkylarkList<?> values, String formatString, Location location)
      throws EvalException;
}
