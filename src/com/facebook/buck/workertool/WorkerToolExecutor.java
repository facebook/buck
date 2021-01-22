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

package com.facebook.buck.workertool;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.facebook.buck.workertool.utils.WorkerToolConstants;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;

/**
 * WorkerTool executor base class that implements that WTv2 protocol. It could launch worker tool
 * and send commands to it using created command's named pipe file.
 */
public abstract class WorkerToolExecutor {

  private static final Logger LOG = Logger.get(WorkerToolExecutor.class);

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();

  private final DownwardApiProcessExecutor downwardApiProcessExecutor;

  public WorkerToolExecutor(DownwardApiProcessExecutor downwardApiProcessExecutor) {
    this.downwardApiProcessExecutor = downwardApiProcessExecutor;
  }

  /** Send an execution command to a worker tool instance and wait till the command executed. */
  public final void executeCommand() throws IOException, InterruptedException {

    try (NamedPipeWriter namedPipeWriter = NAMED_PIPE_FACTORY.createAsWriter();
        OutputStream outputStream = namedPipeWriter.getOutputStream()) {

      LaunchedProcess launchedProcess =
          downwardApiProcessExecutor.launchProcess(
              ProcessExecutorParams.builder()
                  .addAllCommand(getStartWorkerToolCommand())
                  .setEnvironment(buildEnvs(namedPipeWriter.getName()))
                  .build());

      CommandTypeMessage executeCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.EXECUTE_COMMAND);
      ExecuteCommand executeCommand =
          ExecuteCommand.newBuilder().setActionId(downwardApiProcessExecutor.getActionId()).build();

      executeCommandTypeMessage.writeDelimitedTo(outputStream);
      executeCommand.writeDelimitedTo(outputStream);
      writeExecuteCommandTo(outputStream);

      CommandTypeMessage shutdownCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.SHUTDOWN_COMMAND);
      shutdownCommandTypeMessage.writeDelimitedTo(outputStream);
      ShutdownCommand shutdownCommand = ShutdownCommand.getDefaultInstance();
      shutdownCommand.writeDelimitedTo(outputStream);

      ProcessExecutor.Result executionResult =
          downwardApiProcessExecutor.execute(
              launchedProcess,
              ImmutableSet.<ProcessExecutor.Option>builder()
                  .add(ProcessExecutor.Option.EXPECTING_STD_OUT)
                  .add(ProcessExecutor.Option.EXPECTING_STD_ERR)
                  .build(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
      int exitCode = executionResult.getExitCode();
      if (exitCode != 0) {
        LOG.error(
            "Exit code: %s%n[stdOut]%n%s%n[stdErr]%n%s%n",
            exitCode,
            executionResult.getStdout().orElse(""),
            executionResult.getStderr().orElse(""));
      }
    }
  }

  protected abstract void writeExecuteCommandTo(OutputStream outputStream) throws IOException;

  /** Returns a command that starts Worker Tool process. */
  protected abstract ImmutableList<String> getStartWorkerToolCommand();

  private CommandTypeMessage getCommandTypeMessage(CommandTypeMessage.CommandType commandType) {
    CommandTypeMessage.Builder builder = CommandTypeMessage.newBuilder();
    builder.setCommandType(commandType);
    return builder.build();
  }

  private ImmutableMap<String, String> buildEnvs(String namedPipeName) {
    return ImmutableMap.of(WorkerToolConstants.ENV_COMMAND_PIPE, namedPipeName);
  }
}
