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

package com.facebook.buck.core.rules.actions.lib;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.lib.args.CommandLine;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.ExecCompatibleCommandLineBuilder;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Action that runs command line applications with provided arguments and environment */
public class RunAction extends AbstractAction {
  @AddToRuleKey private final CommandLineArgs args;
  @AddToRuleKey private final ImmutableMap<String, String> env;

  /**
   * @param registry the {@link ActionRegistry} to registry this action for.
   * @param shortName the short name to use in logging, console activity, etc. See {@link
   *     #getShortName()}
   * @param args the arguments to evaluate and use when executing the application. This evaluation
   *     is not done until the action is executed, and at least one argument must be provided.
   * @param env any environment variables that should override the original environment.
   */
  public RunAction(
      ActionRegistry registry,
      String shortName,
      CommandLineArgs args,
      ImmutableMap<String, String> env) {
    this(registry, getAllInputsAndOutputs(args), shortName, args, env);
  }

  private RunAction(
      ActionRegistry registry,
      Pair<ImmutableSortedSet<Artifact>, ImmutableSortedSet<OutputArtifact>> inputsAndOutputs,
      String shortName,
      CommandLineArgs args,
      ImmutableMap<String, String> env) {
    super(registry, inputsAndOutputs.getFirst(), inputsAndOutputs.getSecond(), shortName);
    this.args = args;
    this.env = env;
  }

  private static Pair<ImmutableSortedSet<Artifact>, ImmutableSortedSet<OutputArtifact>>
      getAllInputsAndOutputs(CommandLineArgs args) {
    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<OutputArtifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);
    return new Pair<>(inputs.build(), outputs.build());
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    ArtifactFilesystem filesystem = executionContext.getArtifactFilesystem();
    CommandLine commandLine;
    try {
      commandLine = new ExecCompatibleCommandLineBuilder(filesystem).build(args);
    } catch (CommandLineArgException e) {
      return ActionExecutionResult.failure(
          Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
    }
    if (commandLine.getCommandLineArgs().isEmpty()) {
      return ActionExecutionResult.failure(
          Optional.empty(),
          Optional.empty(),
          ImmutableList.of(),
          Optional.of(
              new HumanReadableException(
                  "Zero arguments were provided when invoking run() action")));
    }

    ImmutableList<String> stringifiedCommandLine = commandLine.getCommandLineArgs();
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setEnvironment(getEnvironment(executionContext))
            .setDirectory(executionContext.getWorkingDirectory())
            .setCommand(stringifiedCommandLine)
            .build();

    try {
      ProcessExecutor.Result result =
          executionContext.getProcessExecutor().launchAndExecute(params);
      Optional<String> stdout = result.getStdout();
      Optional<String> stderr = result.getStderr();
      ImmutableList<String> command = result.getCommand();
      if (result.getExitCode() == 0) {
        return ActionExecutionResult.success(stdout, stderr, command);
      } else {
        return ActionExecutionResult.failure(
            stdout,
            stderr,
            command,
            Optional.of(new ProcessExecutionFailedException(result.getExitCode())));
      }
    } catch (InterruptedException | IOException e) {
      return ActionExecutionResult.failure(
          Optional.empty(), Optional.empty(), stringifiedCommandLine, Optional.of(e));
    }
  }

  private ImmutableMap<String, String> getEnvironment(ActionExecutionContext context) {
    Map<String, String> contextEnv = context.getEnvironment();
    HashMap<String, String> newEnv = new HashMap<>(contextEnv.size() + env.size());

    newEnv.putAll(contextEnv);
    newEnv.put("PWD", context.getWorkingDirectory().toString());
    newEnv.putAll(env);
    return ImmutableMap.copyOf(newEnv);
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
