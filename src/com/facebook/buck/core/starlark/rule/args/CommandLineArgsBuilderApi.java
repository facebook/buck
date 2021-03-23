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
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

/**
 * Struct for creating more efficient and validated lists of arguments to pass to actions' command
 * lines. Exposed via ctx.actions.args()
 */
@StarlarkBuiltin(
    name = "args",
    doc = "Struct for creating lists of arguments to use in command lines of actions")
public interface CommandLineArgsBuilderApi {
  @StarlarkMethod(
      name = "add",
      doc =
          "Adds an argument to an existing `Args` object. Returns the original `Args` object "
              + "after modifying it.",
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
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = Artifact.class),
              @ParamType(type = Label.class),
              @ParamType(type = OutputArtifact.class),
              @ParamType(type = CommandLineArgsApi.class)
            }),
        @Param(
            name = "value",
            defaultValue = "None",
            doc =
                "If provided, the value to add. e.g. `ctx.actions.args().add(\"--foo\", \"bar\")` "
                    + "would add `\"--foo\"` and `\"bar\"`. This can be more convenient for the "
                    + "common \"--flag value\" pattern; however, for longer lists of arguments, "
                    + "use `Args.add_all()`",
            named = false,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = Artifact.class),
              @ParamType(type = Label.class),
              @ParamType(type = OutputArtifact.class),
              @ParamType(type = CommandLineArgsApi.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "format",
            doc =
                "A format string to apply after stringifying each argument. This must contain one "
                    + "or more %s. Each will be replaced with the string value of each argument "
                    + "at execution time.",
            allowedTypes = @ParamType(type = String.class),
            named = true,
            positional = false,
            defaultValue = "\"%s\"")
      })
  CommandLineArgsBuilderApi add(Object argNameOrValue, Object value, String formatString)
      throws EvalException;

  @StarlarkMethod(
      name = "add_all",
      doc =
          "Adds a list of arguments to an existing `Args` object. Returns the original `Args` "
              + "object after modifying it.",
      parameters = {
        @Param(
            name = "values",
            doc =
                "Values to add to the existing `Args` object. See `args.add()` for type restrictions",
            allowedTypes = @ParamType(type = StarlarkList.class)),
        @Param(
            name = "format",
            doc =
                "A format string to apply after stringifying each argument. This must contain one "
                    + "or more %s. Each will be replaced with the string value of each argument "
                    + "at execution time.",
            allowedTypes = @ParamType(type = String.class),
            named = true,
            defaultValue = "\"%s\"")
      })
  CommandLineArgsBuilderApi addAll(StarlarkList<?> values, String formatString)
      throws EvalException;
}
