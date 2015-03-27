/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.Map;

import javax.annotation.Nullable;

public abstract class AbstractGenruleStep extends ShellStep {

  private final CommandString commandString;
  private final BuildTarget target;

  public AbstractGenruleStep(
      BuildTarget target,
      CommandString commandString,
      @Nullable File workingDirectory) {
    super(workingDirectory);
    this.target = target;
    this.commandString = commandString;
  }

  @Override
  public String getShortName() {
    return "genrule";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ExecutionArgsAndCommand commandAndExecutionArgs = context.getPlatform() == Platform.WINDOWS ?
        commandString.getExpandedCommandAndExecutionArgs(
            Platform.WINDOWS,
            getEnvironmentVariables(context),
            target) :
        commandString.getCommandAndExecutionArgs(context.getPlatform(), target);
    return ImmutableList.<String>builder()
        .addAll(commandAndExecutionArgs.executionArgs)
        .add(commandAndExecutionArgs.command)
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    ImmutableMap.Builder<String, String> allEnvironmentVariablesBuilder = ImmutableMap.builder();
    addEnvironmentVariables(context, allEnvironmentVariablesBuilder);
    ImmutableMap<String, String> allEnvironmentVariables = allEnvironmentVariablesBuilder.build();

    // Long lists of environment variables can extend the length of the command such that it exceeds
    // exec()'s ARG_MAX limit. Defend against this by filtering out variables that do not appear in
    // the command string.
    String command =
        commandString.getCommandAndExecutionArgs(context.getPlatform(), target).command;
    ImmutableMap.Builder<String, String> usedEnvironmentVariablesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> environmentVariable : allEnvironmentVariables.entrySet()) {
      // We check for the presence of the variable without adornment for $ or %% so it works on both
      // Windows and non-Windows environments. Eventually, we will require $ in the command string
      // and modify the command directly rather than using environment variables.
      String environmentVariableName = environmentVariable.getKey();
      if (command.contains(environmentVariableName)) {
        // I hate this $DEPS variable so much...
        if ("DEPS".equals(environmentVariableName) &&
            allEnvironmentVariables.containsKey("GEN_DIR")) {
          usedEnvironmentVariablesBuilder.put("GEN_DIR", allEnvironmentVariables.get("GEN_DIR"));
        }
        usedEnvironmentVariablesBuilder.put(environmentVariable);
      }
    }
    return usedEnvironmentVariablesBuilder.build();
  }

  protected abstract void addEnvironmentVariables(ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder);

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return true;
  }

  private static class ExecutionArgsAndCommand {

    private final ImmutableList<String> executionArgs;
    private final String command;
    private ExecutionArgsAndCommand(ImmutableList<String> executionArgs, String command) {
      this.executionArgs = executionArgs;
      this.command = command;
    }

  }

  public static class CommandString {
    private Optional<String> cmd;
    private Optional<String> bash;
    private Optional<String> cmdExe;

    public CommandString(Optional<String> cmd, Optional<String> bash, Optional<String> cmdExe) {
      this.cmd = cmd;
      this.bash = bash;
      this.cmdExe = cmdExe;
    }

    public ExecutionArgsAndCommand getCommandAndExecutionArgs(
        Platform platform,
        BuildTarget target) {
      // The priority sequence is
      //   "cmd.exe /c winCommand" (Windows Only)
      //   "/bin/bash -e -c shCommand" (Non-windows Only)
      //   "(/bin/bash -c) or (cmd.exe /c) cmd" (All platforms)
      String command;
      if (platform == Platform.WINDOWS) {
        if (!cmdExe.or("").isEmpty()) {
          command = cmdExe.get();
        } else if (!cmd.or("").isEmpty()) {
          command = cmd.get();
        } else {
          throw new HumanReadableException("You must specify either cmd_exe or cmd for genrule %s.",
              target.getFullyQualifiedName());
        }
        return new ExecutionArgsAndCommand(ImmutableList.of("cmd.exe", "/c"), command);
      } else {
        if (!bash.or("").isEmpty()) {
          command = bash.get();
        } else if (!cmd.or("").isEmpty()) {
          command = cmd.get();
        } else {
          throw new HumanReadableException("You must specify either bash or cmd for genrule %s.",
              target.getFullyQualifiedName());
        }
        return new ExecutionArgsAndCommand(ImmutableList.of("/bin/bash", "-e", "-c"), command);
      }
    }

    public ExecutionArgsAndCommand getExpandedCommandAndExecutionArgs(
        Platform platform,
        ImmutableMap<String, String> environmentVariablesToExpand,
        BuildTarget target) {
      ExecutionArgsAndCommand original = getCommandAndExecutionArgs(platform, target);
      String expandedCommand = original.command;
      for (Map.Entry<String, String> variable : environmentVariablesToExpand.entrySet()) {
        expandedCommand = original.command
            .replace("$" + variable.getKey(), variable.getValue())
            .replace("${" + variable.getKey() + "}", variable.getValue());
      }
      return new ExecutionArgsAndCommand(original.executionArgs, expandedCommand);
    }
  }
}
