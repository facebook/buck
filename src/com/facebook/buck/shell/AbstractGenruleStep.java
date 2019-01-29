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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.programrunner.DirectProgramRunner;
import com.facebook.buck.shell.programrunner.ProgramRunner;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public abstract class AbstractGenruleStep extends ShellStep {

  private final ProjectFilesystem projectFilesystem;
  private final CommandString commandString;
  private final ProgramRunner programRunner;
  private final LoadingCache<Platform, Path> scriptFilePath =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Platform, Path>() {
                @Override
                public Path load(Platform platform) throws IOException {
                  ExecutionArgsAndCommand executionArgsAndCommand =
                      getExecutionArgsAndCommand(platform);
                  return projectFilesystem.resolve(
                      projectFilesystem.createTempFile(
                          "genrule-", "." + executionArgsAndCommand.shellType.extension));
                }
              });

  public AbstractGenruleStep(
      ProjectFilesystem projectFilesystem,
      CommandString commandString,
      Path workingDirectory,
      ProgramRunner programRunner) {
    super(workingDirectory);
    this.projectFilesystem = projectFilesystem;
    this.commandString = commandString;
    this.programRunner = programRunner;
  }

  public AbstractGenruleStep(
      ProjectFilesystem projectFilesystem, CommandString commandString, Path workingDirectory) {
    this(projectFilesystem, commandString, workingDirectory, new DirectProgramRunner());
  }

  @Override
  public String getShortName() {
    return "genrule";
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path scriptFilePath = getScriptFilePath(context);
    String scriptFileContents = getScriptFileContents(context);
    projectFilesystem.writeContentsToPath(
        scriptFileContents + System.lineSeparator(), scriptFilePath);

    programRunner.prepareForRun(projectFilesystem, scriptFilePath);

    return super.execute(context);
  }

  private ImmutableList<String> getCommandLine(ExecutionContext context) {
    ExecutionArgsAndCommand executionArgsAndCommand =
        getExecutionArgsAndCommand(context.getPlatform());
    ImmutableList.Builder<String> commandLineBuilder = ImmutableList.builder();

    Path scriptFilePath = this.scriptFilePath.getUnchecked(context.getPlatform());

    return commandLineBuilder
        .addAll(executionArgsAndCommand.shellType.executionArgs)
        .add(scriptFilePath.toString())
        .build();
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return programRunner.enhanceCommandLine(getCommandLine(context));
  }

  @Override
  protected ImmutableList<String> getShellCommandArgsForDescription(ExecutionContext context) {
    return programRunner.enhanceCommandLineForDescription(getCommandLine(context));
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    ImmutableMap.Builder<String, String> allEnvironmentVariablesBuilder = ImmutableMap.builder();
    addEnvironmentVariables(context, allEnvironmentVariablesBuilder);
    ImmutableMap<String, String> allEnvironmentVariables = allEnvironmentVariablesBuilder.build();

    // Long lists of environment variables can extend the length of the command such that it exceeds
    // exec()'s ARG_MAX limit. Defend against this by filtering out variables that do not appear in
    // the command string.
    String command = getExecutionArgsAndCommand(context.getPlatform()).command;
    ImmutableMap.Builder<String, String> usedEnvironmentVariablesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> environmentVariable : allEnvironmentVariables.entrySet()) {
      // We check for the presence of the variable without adornment for $ or %% so it works on both
      // Windows and non-Windows environments. Eventually, we will require $ in the command string
      // and modify the command directly rather than using environment variables.
      String environmentVariableName = environmentVariable.getKey();
      if (command.contains(environmentVariableName)) {
        usedEnvironmentVariablesBuilder.put(environmentVariable);
      }
    }
    return usedEnvironmentVariablesBuilder.build();
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return true;
  }

  @VisibleForTesting
  public String getScriptFileContents(ExecutionContext context) {
    ExecutionArgsAndCommand executionArgsAndCommand =
        getExecutionArgsAndCommand(context.getPlatform());
    if (context.getPlatform() == Platform.WINDOWS) {
      executionArgsAndCommand =
          getExpandedCommandAndExecutionArgs(
              executionArgsAndCommand, getEnvironmentVariables(context));
    }
    return executionArgsAndCommand.command;
  }

  @VisibleForTesting
  public Path getScriptFilePath(ExecutionContext context) throws IOException {
    try {
      return scriptFilePath.get(context.getPlatform());
    } catch (Exception e) {
      Throwables.propagateIfPossible(e, IOException.class);
      throw new RuntimeException(e);
    }
  }

  private ExecutionArgsAndCommand getExecutionArgsAndCommand(Platform platform) {
    return commandString.getCommandAndExecutionArgs(platform);
  }

  protected abstract void addEnvironmentVariables(
      ExecutionContext context, ImmutableMap.Builder<String, String> environmentVariablesBuilder);

  private static ExecutionArgsAndCommand getExpandedCommandAndExecutionArgs(
      ExecutionArgsAndCommand original, ImmutableMap<String, String> environmentVariablesToExpand) {
    String expandedCommand = original.command;
    for (Map.Entry<String, String> variable : environmentVariablesToExpand.entrySet()) {
      expandedCommand =
          expandedCommand
              .replace("$" + variable.getKey(), variable.getValue())
              .replace("${" + variable.getKey() + "}", variable.getValue());
    }
    return new ExecutionArgsAndCommand(original.shellType, expandedCommand);
  }

  private static class ExecutionArgsAndCommand {

    private final ShellType shellType;
    private final String command;

    private ExecutionArgsAndCommand(ShellType shellType, String command) {
      this.shellType = shellType;
      this.command = command;
    }
  }

  private enum ShellType {
    CMD_EXE("cmd", ImmutableList.of()),
    BASH("sh", ImmutableList.of("/bin/bash", "-e")),
    ;
    private final String extension;
    private final ImmutableList<String> executionArgs;

    ShellType(String extension, ImmutableList<String> executionArgs) {
      this.extension = extension;
      this.executionArgs = executionArgs;
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

    private ExecutionArgsAndCommand getCommandAndExecutionArgs(Platform platform) {
      // The priority sequence is
      //   "cmd.exe /c winCommand" (Windows Only)
      //   "/bin/bash -e -c shCommand" (Non-windows Only)
      //   "(/bin/bash -c) or (cmd.exe /c) cmd" (All platforms)
      String command;
      if (platform == Platform.WINDOWS) {
        if (!cmdExe.orElse("").isEmpty()) {
          command = cmdExe.get();
        } else if (!cmd.orElse("").isEmpty()) {
          command = cmd.get();
        } else {
          throw new HumanReadableException("You must specify either cmd_exe or cmd for genrule.");
        }
        return new ExecutionArgsAndCommand(ShellType.CMD_EXE, command);
      } else {
        if (!bash.orElse("").isEmpty()) {
          command = bash.get();
        } else if (!cmd.orElse("").isEmpty()) {
          command = cmd.get();
        } else {
          throw new HumanReadableException("You must specify either bash or cmd for genrule.");
        }
        return new ExecutionArgsAndCommand(ShellType.BASH, command);
      }
    }
  }
}
