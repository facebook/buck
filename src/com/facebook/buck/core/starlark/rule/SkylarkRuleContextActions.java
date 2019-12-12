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
import com.facebook.buck.core.artifact.ArtifactDeclarationException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.lib.RunAction;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilder;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilderApi;
import com.facebook.buck.util.CommandLineException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Container for all methods that create actions within the implementation function of a user
 * defined rule
 */
public class SkylarkRuleContextActions implements SkylarkRuleContextActionsApi {

  private final ActionRegistry registry;

  public SkylarkRuleContextActions(ActionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Artifact declareFile(String path, Location location) throws EvalException {
    try {
      return registry.declareArtifact(Paths.get(path), location);
    } catch (InvalidPathException e) {
      throw new EvalException(location, String.format("Invalid path '%s' provided", path));
    } catch (ArtifactDeclarationException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
  }

  @Override
  public void write(Artifact output, String content, boolean isExecutable, Location location)
      throws EvalException {
    try {
      new WriteAction(
          registry, ImmutableSortedSet.of(), ImmutableSortedSet.of(output), content, isExecutable);
    } catch (HumanReadableException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
  }

  @Override
  public CommandLineArgsBuilderApi args() {
    return new CommandLineArgsBuilder();
  }

  private static Object validateStringOrArg(Object arg) throws CommandLineArgException {
    if (arg instanceof CommandLineArgsBuilder) {
      return ((CommandLineArgsBuilder) arg).build();
    } else if (arg instanceof CommandLineArgs || arg instanceof String) {
      return arg;
    } else {
      throw new CommandLineArgException(arg);
    }
  }

  @Override
  public void run(
      SkylarkList<Artifact> outputs,
      SkylarkList<Artifact> inputs,
      Object executable,
      SkylarkList<Object> arguments,
      Object shortName,
      Object userEnv,
      Location location)
      throws EvalException {
    // TODO(pjameson): If no inputs are specified (NONE), extract them from the command line
    // parameters. Also convert 'executable' to an input
    List<Artifact> outputsValidated = outputs.getContents(Artifact.class, "outputs");
    List<Artifact> inputsValidated = inputs.getContents(Artifact.class, "inputs");

    Map<String, String> userEnvValidated =
        SkylarkDict.castSkylarkDictOrNoneToDict(userEnv, String.class, String.class, null);

    CommandLineArgs argumentsValidated;
    try {
      argumentsValidated =
          CommandLineArgsFactory.from(
              Stream.concat(
                      Stream.of(CommandLineArgsFactory.from(ImmutableList.of(executable))),
                      arguments.stream())
                  .map(SkylarkRuleContextActions::validateStringOrArg)
                  .collect(ImmutableList.toImmutableList()));
    } catch (CommandLineException e) {
      throw new EvalException(
          location,
          String.format(
              "Invalid type for %s. Must be one of string, int, Artifact, Label, or the result of ctx.actions.args()",
              e.getHumanReadableErrorMessage()));
    }
    // TODO(pjameson): This name needs to be changed to something more reasonable if not provided
    //                 probably something like run action: %s
    String shortNameValidated = EvalUtils.isNullOrNone(shortName) ? "" : (String) shortName;

    new RunAction(
        registry,
        ImmutableSortedSet.copyOf(inputsValidated),
        ImmutableSortedSet.copyOf(outputsValidated),
        shortNameValidated,
        argumentsValidated,
        ImmutableMap.copyOf(userEnvValidated));
  }
}
