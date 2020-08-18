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

package com.facebook.buck.shell;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
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
                          "genrule-", "." + executionArgsAndCommand.getShellType().extension));
                }
              });

  public AbstractGenruleStep(
      ProjectFilesystem projectFilesystem,
      CommandString commandString,
      Path workingDirectory,
      boolean withDownwardApi,
      ProgramRunner programRunner) {
    super(workingDirectory, withDownwardApi);
    this.projectFilesystem = projectFilesystem;
    this.commandString = commandString;
    this.programRunner = programRunner;
  }

  public AbstractGenruleStep(
      ProjectFilesystem projectFilesystem,
      CommandString commandString,
      Path workingDirectory,
      boolean withDownwardApi) {
    this(
        projectFilesystem,
        commandString,
        workingDirectory,
        withDownwardApi,
        new DirectProgramRunner());
  }

  public AbstractGenruleStep(
      ProjectFilesystem projectFilesystem,
      CommandString commandString,
      AbsPath workingDirectory,
      boolean withDownwardApi) {
    this(projectFilesystem, commandString, workingDirectory.getPath(), withDownwardApi);
  }

  @Override
  public String getShortName() {
    return "genrule";
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    Path scriptFilePath = getScriptFilePath(context);
    String scriptFileContents = getScriptFileContents(context);
    projectFilesystem.writeContentsToPath(
        scriptFileContents + System.lineSeparator(), scriptFilePath);

    programRunner.prepareForRun(projectFilesystem, scriptFilePath);

    return super.execute(context);
  }

  private ImmutableList<String> getCommandLine(IsolatedExecutionContext context) {
    ExecutionArgsAndCommand executionArgsAndCommand =
        getExecutionArgsAndCommand(context.getPlatform());
    ImmutableList.Builder<String> commandLineBuilder = ImmutableList.builder();

    Path scriptFilePath = this.scriptFilePath.getUnchecked(context.getPlatform());

    return commandLineBuilder
        .addAll(executionArgsAndCommand.getShellType().executionArgs)
        .add(scriptFilePath.toString())
        .build();
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(StepExecutionContext context) {
    return programRunner.enhanceCommandLine(getCommandLine(context));
  }

  @Override
  protected ImmutableList<String> getShellCommandArgsForDescription(StepExecutionContext context) {
    return programRunner.enhanceCommandLineForDescription(getCommandLine(context));
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    ImmutableMap.Builder<String, String> allEnvironmentVariablesBuilder = ImmutableMap.builder();
    addEnvironmentVariables(allEnvironmentVariablesBuilder);
    ImmutableMap<String, String> allEnvironmentVariables = allEnvironmentVariablesBuilder.build();

    // Long lists of environment variables can extend the length of the command such that it exceeds
    // exec()'s ARG_MAX limit. Defend against this by filtering out variables that do not appear in
    // the command string.
    String command = getExecutionArgsAndCommand(platform).getCommandString();
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
  public boolean shouldPrintStderr(Verbosity verbosity) {
    return true;
  }

  @VisibleForTesting
  public String getScriptFileContents(IsolatedExecutionContext context) {
    Platform platform = context.getPlatform();
    ExecutionArgsAndCommand executionArgsAndCommand = getExecutionArgsAndCommand(platform);
    if (platform == Platform.WINDOWS) {
      executionArgsAndCommand =
          getExpandedCommandAndExecutionArgs(
              (WindowsCmdExeExecutionArgsAndCommand) executionArgsAndCommand,
              getEnvironmentVariables(platform));
    }
    return executionArgsAndCommand.getCommandString();
  }

  @VisibleForTesting
  public Path getScriptFilePath(IsolatedExecutionContext context) throws IOException {
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
      ImmutableMap.Builder<String, String> environmentVariablesBuilder);

  private static WindowsCmdExeEscaper.Command replaceVariableInCmdExeCommand(
      WindowsCmdExeEscaper.Command cmdExeCommand, String variableName, String variableValue) {
    WindowsCmdExeEscaper.Command.Builder replacementCommandBuilder =
        WindowsCmdExeEscaper.Command.builder();

    for (WindowsCmdExeEscaper.CommandSubstring substring : cmdExeCommand.getSubstrings()) {
      if (substring instanceof WindowsCmdExeEscaper.EscapedCommandSubstring) {
        String substringRemainder = substring.string;
        while (!substringRemainder.isEmpty()) {
          int i = substringRemainder.indexOf(variableName);
          if (i != -1) {
            replacementCommandBuilder.appendEscapedSubstring(substringRemainder.substring(0, i));
            replacementCommandBuilder.appendUnescapedSubstring(variableValue);
            substringRemainder = substringRemainder.substring(i + variableName.length());
          } else {
            replacementCommandBuilder.appendEscapedSubstring(substringRemainder);
            break;
          }
        }
      } else if (substring instanceof WindowsCmdExeEscaper.UnescapedCommandSubstring) {
        replacementCommandBuilder.appendUnescapedSubstring(
            substring.string.replace(variableName, variableValue));
      }
    }

    return replacementCommandBuilder.build();
  }

  private static WindowsCmdExeExecutionArgsAndCommand getExpandedCommandAndExecutionArgs(
      WindowsCmdExeExecutionArgsAndCommand original,
      ImmutableMap<String, String> environmentVariablesToExpand) {
    WindowsCmdExeEscaper.Command expandedCommand = original.command;
    for (Map.Entry<String, String> variable : environmentVariablesToExpand.entrySet()) {
      expandedCommand =
          replaceVariableInCmdExeCommand(
              expandedCommand, "$" + variable.getKey(), variable.getValue());
      expandedCommand =
          replaceVariableInCmdExeCommand(
              expandedCommand, "${" + variable.getKey() + "}", variable.getValue());
    }

    return new WindowsCmdExeExecutionArgsAndCommand(expandedCommand);
  }

  private interface ExecutionArgsAndCommand {
    public ShellType getShellType();

    public String getCommandString();
  }

  private static class BashExecutionArgsAndCommand implements ExecutionArgsAndCommand {

    private String command;

    public BashExecutionArgsAndCommand(String command) {
      this.command = command;
    }

    @Override
    public ShellType getShellType() {
      return ShellType.BASH;
    }

    @Override
    public String getCommandString() {
      return command;
    }
  }

  private static class WindowsCmdExeExecutionArgsAndCommand implements ExecutionArgsAndCommand {

    public final WindowsCmdExeEscaper.Command command;

    public WindowsCmdExeExecutionArgsAndCommand(WindowsCmdExeEscaper.Command command) {
      this.command = command;
    }

    @Override
    public ShellType getShellType() {
      return ShellType.CMD_EXE;
    }

    @Override
    public String getCommandString() {
      return WindowsCmdExeEscaper.escape(command);
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
    private Optional<String> cmdForBash;
    private Optional<WindowsCmdExeEscaper.Command> cmdForCmdExe;
    private Optional<String> bash;
    private Optional<WindowsCmdExeEscaper.Command> cmdExe;

    public CommandString(
        Optional<String> cmdForBash,
        Optional<WindowsCmdExeEscaper.Command> cmdForCmdExe,
        Optional<String> bash,
        Optional<WindowsCmdExeEscaper.Command> cmdExe) {
      this.cmdForBash = cmdForBash;
      this.cmdForCmdExe = cmdForCmdExe;
      this.bash = bash;
      this.cmdExe = cmdExe;
    }

    private ExecutionArgsAndCommand getCommandAndExecutionArgs(Platform platform) {
      // The priority sequence is
      //   "cmd.exe /c winCommand" (Windows Only)
      //   "/bin/bash -e -c shCommand" (Non-windows Only)
      //   "(/bin/bash -c) or (cmd.exe /c) cmd" (All platforms)
      if (platform == Platform.WINDOWS) {
        WindowsCmdExeEscaper.Command command;
        if (cmdExe.isPresent()) {
          command = cmdExe.get();
        } else if (cmdForCmdExe.isPresent()) {
          command = cmdForCmdExe.get();
        } else {
          throw new HumanReadableException("You must specify either cmd_exe or cmd for genrule.");
        }
        return new WindowsCmdExeExecutionArgsAndCommand(command);
      } else {
        String command;
        if (!bash.orElse("").isEmpty()) {
          command = bash.get();
        } else if (!cmdForBash.orElse("").isEmpty()) {
          command = cmdForBash.get();
        } else {
          throw new HumanReadableException("You must specify either bash or cmd for genrule.");
        }
        return new BashExecutionArgsAndCommand(command);
      }
    }
  }
}
