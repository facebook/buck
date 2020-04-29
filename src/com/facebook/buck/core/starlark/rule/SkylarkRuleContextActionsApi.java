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
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkValue;

/**
 * Struct containing methods that create actions within the implementation function of a user
 * defined rule
 */
@SkylarkModule(
    name = "actions",
    title = "actions",
    doc = "Struct containing methods to create actions within a rule's implementation method")
public interface SkylarkRuleContextActionsApi extends StarlarkValue {

  @SkylarkCallable(
      name = "args",
      doc = "Get an instance of `Args` to construct command lines for actions",
      useLocation = true,
      parameters = {
        @Param(
            name = "args",
            doc =
                "Values to initialize the new Args object with. If a list is provided, "
                    + "these args are passed to `Args.add_all`. If a single non-list item is "
                    + "provided, it is passed to `Args.add`.",
            defaultValue = "None",
            noneable = true,
            type = Object.class),
        @Param(
            name = "format",
            doc =
                "A format string to apply after stringifying each argument. This must contain one "
                    + "or more %s. Each will be replaced with the string value of each argument "
                    + "at execution time.",
            type = String.class,
            named = true,
            defaultValue = "\"%s\"")
      })
  CommandLineArgsBuilderApi args(Object args, String formatString, Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "copy_file",
      doc = "Copies a file from `src` to `dst`",
      useLocation = true,
      parameters = {
        @Param(name = "src", doc = "The file to copy", type = Artifact.class, named = true),
        @Param(
            name = "dest",
            doc =
                "The destination to copy to. This may either be a file declared with "
                    + "`declare_file` or in an `output` attribute, or a string that will be used "
                    + "to declare a new file (which is returned by this function)",
            type = Object.class,
            allowedTypes = {@ParamType(type = Artifact.class), @ParamType(type = String.class)},
            named = true),
      })
  Artifact copyFile(Artifact src, Object dest, Location location) throws EvalException;

  @SkylarkCallable(
      name = "declare_file",
      doc =
          "Creates an `Artifact` for the given filename within this build rule. "
              + "The returned `Artifact` must be used as an output by an action within the same "
              + "rule in which it is declared.",
      useLocation = true,
      parameters = {
        @Param(
            name = "filename",
            doc =
                "The name of the file that will be created. This must be relative and not traverse "
                    + "upward in the filesystem",
            type = String.class,
            named = true)
      })
  Artifact declareFile(String path, Location location) throws EvalException;

  @SkylarkCallable(
      name = "run",
      doc = "Creates an action that runs a given executable in a specific environment.",
      useLocation = true,
      parameters = {
        @Param(
            name = "arguments",
            doc =
                "The arguments to run. After all elements of this list are combined, there must "
                    + "be at least one argument. Empty lists will result in a build failure. Any "
                    + "`OutputArtifact`s must be written to by the specified executable.",
            named = true,
            type = StarlarkList.class,
            defaultValue = "[]"),
        @Param(
            name = "short_name",
            doc = "The short name to display for this action in logs and the console",
            named = true,
            noneable = true,
            type = String.class,
            defaultValue = "None"),
        @Param(
            name = "env",
            doc = "Environment variables that should be set when this action is executed",
            named = true,
            noneable = true,
            type = Dict.class,
            defaultValue = "None")
      })
  void run(StarlarkList<Object> arguments, Object shortName, Object userEnv, Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "write",
      doc =
          "Creates a file write action. When the action is executed, it will write the given "
              + "content to a file. This is used to generate files using information available "
              + "when a rule's implementation function is called.",
      useLocation = true,
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
              @ParamType(type = CommandLineArgsApi.class),
              @ParamType(type = String.class)
            },
            named = true),
        @Param(
            name = "is_executable",
            doc = "For Posix platforms, whether this file should be made executable",
            type = Boolean.class,
            named = true,
            defaultValue = "False")
      })
  Artifact write(Object output, Object content, boolean isExecutable, Location location)
      throws EvalException;
}
