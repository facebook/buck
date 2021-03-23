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

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilderApi;
import com.facebook.buck.core.starlark.rule.artifact.SkylarkArtifactApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * Struct containing methods that create actions within the implementation function of a user
 * defined rule
 */
@StarlarkBuiltin(
    name = "actions",
    doc = "Struct containing methods to create actions within a rule's implementation method")
public interface SkylarkRuleContextActionsApi {

  @StarlarkMethod(
      name = "args",
      doc = "Get an instance of `Args` to construct command lines for actions",
      parameters = {
        @Param(
            name = "args",
            doc =
                "Values to initialize the new Args object with. If a list is provided, "
                    + "these args are passed to `Args.add_all`. If a single non-list item is "
                    + "provided, it is passed to `Args.add`.",
            defaultValue = "None"),
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
  CommandLineArgsBuilderApi args(Object args, String formatString) throws EvalException;

  @StarlarkMethod(
      name = "copy_file",
      doc = "Copies a file from `src` to `dst`",
      parameters = {
        @Param(
            name = "src",
            doc = "The file to copy",
            allowedTypes = @ParamType(type = Artifact.class),
            named = true),
        @Param(
            name = "dest",
            doc =
                "The destination to copy to. This may either be a file declared with "
                    + "`declare_file` or in an `output` attribute, or a string that will be used "
                    + "to declare a new file (which is returned by this function)",
            allowedTypes = {@ParamType(type = Artifact.class), @ParamType(type = String.class)},
            named = true),
      },
      useStarlarkThread = true)
  Artifact copyFile(Artifact src, Object dest, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "declare_file",
      doc =
          "Creates an `Artifact` for the given filename within this build rule. "
              + "The returned `Artifact` must be used as an output by an action within the same "
              + "rule in which it is declared.",
      parameters = {
        @Param(
            name = "filename",
            doc =
                "The name of the file that will be created. This must be relative and not traverse "
                    + "upward in the filesystem",
            allowedTypes = @ParamType(type = String.class),
            named = true)
      },
      useStarlarkThread = true)
  Artifact declareFile(String path, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "run",
      doc = "Creates an action that runs a given executable in a specific environment.",
      parameters = {
        @Param(
            name = "arguments",
            doc =
                "The arguments to run. After all elements of this list are combined, there must "
                    + "be at least one argument. Empty lists will result in a build failure. Any "
                    + "`OutputArtifact`s must be written to by the specified executable.",
            named = true,
            allowedTypes = @ParamType(type = StarlarkList.class),
            defaultValue = "[]"),
        @Param(
            name = "short_name",
            doc = "The short name to display for this action in logs and the console",
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "None"),
        @Param(
            name = "env",
            doc = "Environment variables that should be set when this action is executed",
            named = true,
            allowedTypes = {@ParamType(type = Dict.class), @ParamType(type = NoneType.class)},
            defaultValue = "None")
      })
  void run(StarlarkList<Object> arguments, Object shortName, Object userEnv) throws EvalException;

  @StarlarkMethod(
      name = "write",
      doc =
          "Creates a file write action. When the action is executed, it will write the given "
              + "content to a file. This is used to generate files using information available "
              + "when a rule's implementation function is called.",
      parameters = {
        @Param(
            name = "output",
            doc =
                "The file to write to. This may either be a file declared with `declare_file` or "
                    + "in an `output` attribute, or a string that will be used to declare a new "
                    + "file (which is returned by this function)",
            allowedTypes = {
              @ParamType(type = SkylarkArtifactApi.class),
              @ParamType(type = String.class)
            },
            named = true),
        @Param(
            name = "content",
            doc =
                "The content to write to this file. If a string, it is written as-is to the "
                    + "file. If it is an instance of `Args`, each argument is stringified and "
                    + "put on a single line by itself in `output`.",
            allowedTypes = {
              @ParamType(type = CommandLineArgsBuilderApi.class),
              @ParamType(type = String.class)
            },
            named = true),
        @Param(
            name = "is_executable",
            doc = "For Posix platforms, whether this file should be made executable",
            allowedTypes = @ParamType(type = Boolean.class),
            named = true,
            defaultValue = "False")
      },
      useStarlarkThread = true)
  Artifact write(Object output, Object content, boolean isExecutable, StarlarkThread thread)
      throws EvalException;
}
