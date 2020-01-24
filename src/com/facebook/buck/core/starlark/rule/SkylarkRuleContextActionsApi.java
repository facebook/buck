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
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;

/**
 * Struct containing methods that create actions within the implementation function of a user
 * defined rule
 */
@SkylarkModule(
    name = "actions",
    title = "actions",
    doc = "Struct containing methods to create actions within a rule's implementation method")
public interface SkylarkRuleContextActionsApi {

  @SkylarkCallable(
      name = "declare_file",
      doc = "Declares a file that will be used as the output by subsequent actions",
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
      name = "copy_file",
      doc = "Copies a file",
      useLocation = true,
      parameters = {
        @Param(name = "src", doc = "The file to copy", type = Artifact.class, named = true),
        @Param(
            name = "dest",
            doc =
                "The destination to copy to. This may either be a file declared with "
                    + "`declare_file`, or a string that will be used to declare a new file "
                    + "(which is returned by this function)",
            type = Object.class,
            allowedTypes = {@ParamType(type = Artifact.class), @ParamType(type = String.class)},
            named = true),
      })
  Artifact copyFile(Artifact src, Object dest, Location location) throws EvalException;

  @SkylarkCallable(
      name = "write",
      doc =
          "Creates a file write action. When the action is executed, it will write the given "
              + "content to a file. This is used to generate files using information available "
              + "in the analysis phase. Returns the artifact in `output`",
      useLocation = true,
      parameters = {
        @Param(
            name = "output",
            doc =
                "The file to write to. This may either be a string, in which case the Artifact "
                    + "is declared for you, or it must have been returned from "
                    + "ctx.actions.declare_file",
            allowedTypes = {
              @ParamType(type = SkylarkArtifactApi.class),
              @ParamType(type = String.class)
            },
            named = true),
        @Param(
            name = "content",
            doc = "The content to write to this file",
            allowedTypes = {
              @ParamType(type = CommandLineArgsBuilderApi.class),
              @ParamType(type = CommandLineArgsApi.class),
              @ParamType(type = String.class)
            },
            named = true),
        @Param(
            name = "is_executable",
            doc = "Whether the file should be marked executable after writing",
            type = Boolean.class,
            named = true,
            defaultValue = "False")
      })
  Artifact write(Object output, Object content, boolean isExecutable, Location location)
      throws EvalException;

  @SkylarkCallable(
      name = "args",
      doc = "Get an instance of Args to construct command lines for actions",
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
      name = "run",
      doc =
          "Creates a run action. When the action is executed it will run the specified executable with the given arguments and environment",
      useLocation = true,
      parameters = {
        @Param(
            name = "arguments",
            doc =
                "The list of arguments to pass to executable. This must be a list containing only strings or objects from ctx.actions.args()",
            named = true,
            type = SkylarkList.class,
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
            type = SkylarkDict.class,
            defaultValue = "None")
      })
  void run(SkylarkList<Object> arguments, Object shortName, Object userEnv, Location location)
      throws EvalException;
}
