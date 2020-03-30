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
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.lib.CopyAction;
import com.facebook.buck.core.rules.actions.lib.RunAction;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilder;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilderApi;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.util.CommandLineException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Map;

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
  public Artifact copyFile(Artifact src, Object dest, Location location) throws EvalException {
    Artifact destArtifact = getArtifactFromArtifactOrString(location, dest);
    new CopyAction(registry, src, destArtifact.asOutputArtifact(), CopySourceMode.FILE);
    return destArtifact;
  }

  @Override
  public Artifact write(Object output, Object content, boolean isExecutable, Location location)
      throws EvalException {
    Artifact destArtifact = getArtifactFromArtifactOrString(location, output);
    try {
      ImmutableSortedSet<OutputArtifact> outputs =
          ImmutableSortedSet.of(destArtifact.asOutputArtifact());
      if (content instanceof String) {
        new WriteAction(registry, ImmutableSortedSet.of(), outputs, (String) content, isExecutable);
      } else if (content instanceof CommandLineArgsBuilder) {
        new WriteAction(
            registry,
            ImmutableSortedSet.of(),
            outputs,
            ((CommandLineArgsBuilder) content).build(),
            isExecutable);
      } else if (content instanceof CommandLineArgs) {
        new WriteAction(
            registry, ImmutableSortedSet.of(), outputs, (CommandLineArgs) content, isExecutable);
      } else {
        throw new EvalException(
            location, String.format("Invalid type for content: %s", content.getClass()));
      }

      return destArtifact;
    } catch (HumanReadableException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
  }

  /**
   * @return An output artifact. If {@code arg} is a string, a new artifact is declared and
   *     returned. If {@code arg} is an artifact, it is returned without any changes
   */
  private Artifact getArtifactFromArtifactOrString(Location location, Object arg)
      throws EvalException {
    Artifact destArtifact;
    if (arg instanceof String) {
      destArtifact = declareFile((String) arg, location);
    } else if (arg instanceof Artifact) {
      destArtifact = (Artifact) arg;
    } else {
      /**
       * Should not be hit; these types are validated in the {@link
       * com.google.devtools.build.lib.skylarkinterface.SkylarkCallable} decorators
       */
      throw new EvalException(location, "Invalid output object provided");
    }
    return destArtifact;
  }

  @Override
  public CommandLineArgsBuilderApi args(Object args, String formatString, Location location)
      throws EvalException {
    CommandLineArgsBuilder builder = new CommandLineArgsBuilder();
    if (!EvalUtils.isNullOrNone(args)) {
      if (args instanceof SkylarkList) {
        builder.addAll((SkylarkList<?>) args, formatString, location);
      } else {
        builder.add(args, Runtime.UNBOUND, formatString, location);
      }
    }
    return builder;
  }

  private static Object getImmutableArg(Object arg) throws CommandLineArgException {
    if (arg instanceof CommandLineArgsBuilder) {
      return ((CommandLineArgsBuilder) arg).build();
    } else {
      return arg;
    }
  }

  @Override
  public void run(
      SkylarkList<Object> arguments, Object shortName, Object userEnv, Location location)
      throws EvalException {
    Map<String, String> userEnvValidated =
        SkylarkDict.castSkylarkDictOrNoneToDict(userEnv, String.class, String.class, null);

    CommandLineArgs argumentsValidated;
    Object firstArgument;
    try {
      argumentsValidated =
          CommandLineArgsFactory.from(
              arguments.stream()
                  .map(SkylarkRuleContextActions::getImmutableArg)
                  .collect(ImmutableList.toImmutableList()));
      firstArgument =
          argumentsValidated
              .getArgsAndFormatStrings()
              .findFirst()
              .orElseThrow(
                  () ->
                      new EvalException(
                          location, "At least one argument must be provided to 'run()'"))
              .getObject();
    } catch (CommandLineException e) {
      throw new EvalException(
          location,
          String.format(
              "Invalid type for %s. Must be one of string, int, Artifact, Label, or the result of ctx.actions.args()",
              e.getHumanReadableErrorMessage()));
    }

    String shortNameValidated;
    if (EvalUtils.isNullOrNone(shortName)) {
      if (firstArgument instanceof Artifact) {
        shortNameValidated =
            String.format("run action %s", ((Artifact) firstArgument).getBasename());
      } else if (firstArgument instanceof OutputArtifact) {
        shortNameValidated =
            String.format(
                "run action %s", ((OutputArtifact) firstArgument).getArtifact().getBasename());
      } else {
        shortNameValidated = String.format("run action %s", firstArgument);
      }
    } else {
      shortNameValidated = (String) shortName;
    }

    new RunAction(
        registry, shortNameValidated, argumentsValidated, ImmutableMap.copyOf(userEnvValidated));
  }
}
