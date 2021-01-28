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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.processexecutor.DefaultNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.facebook.buck.workertool.utils.WorkerToolConstants;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * WorkerTool executor base class that implements that WTv2 protocol. It could launch worker tool
 * and send commands to it using created command's named pipe file.
 */
public abstract class WorkerToolExecutor {

  private static final Logger LOG = Logger.get(WorkerToolExecutor.class);

  private static final long SHUTDOWN_TIMEOUT = 2;
  private static final TimeUnit SHUTDOWN_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();

  private final DownwardApiProcessExecutor downwardApiProcessExecutor;

  private NamedPipeWriter namedPipeWriter;
  private OutputStream outputStream;
  private LaunchedProcess launchedProcess;

  private String executingActionId;
  @Nullable private SettableFuture<ResultEvent> resultEventFuture;

  public WorkerToolExecutor(IsolatedExecutionContext isolatedExecutionContext) {
    this.downwardApiProcessExecutor =
        isolatedExecutionContext.getDownwardApiProcessExecutor(
            WorkerToolExecutorNamedPipeEventHandler::new);
  }

  private class WorkerToolExecutorNamedPipeEventHandler extends DefaultNamedPipeEventHandler {

    WorkerToolExecutorNamedPipeEventHandler(
        NamedPipeReader namedPipe, DownwardApiExecutionContext context) {
      super(namedPipe, context);
    }

    @Override
    protected void processEvent(EventType eventType, AbstractMessage event) {
      if (eventType == EventType.RESULT_EVENT) {
        processResultEvent((ResultEvent) event);
      } else {
        super.processEvent(eventType, event);
      }
    }

    private void processResultEvent(ResultEvent resultEvent) {
      String actionId = resultEvent.getActionId();
      LOG.info("Received result event for action id: %s", actionId);
      Preconditions.checkState(executingActionId.equals(actionId));
      resultEventFuture.set(resultEvent);
    }
  }

  /** Launches worker tool. */
  public void launchWorker() throws IOException {
    namedPipeWriter = NAMED_PIPE_FACTORY.createAsWriter();
    outputStream = namedPipeWriter.getOutputStream();
    launchedProcess =
        downwardApiProcessExecutor.launchProcess(
            ProcessExecutorParams.builder()
                .addAllCommand(getStartWorkerToolCommand())
                .setEnvironment(buildEnvs(namedPipeWriter.getName()))
                .build());
  }

  /** Send an execution command to a worker tool instance and wait till the command executed. */
  public final ResultEvent executeCommand(String actionId, AbstractMessage executeCommandMessage)
      throws IOException, ExecutionException, InterruptedException {
    CommandTypeMessage executeCommandTypeMessage =
        getCommandTypeMessage(CommandTypeMessage.CommandType.EXECUTE_COMMAND);
    ExecuteCommand executeCommand = ExecuteCommand.newBuilder().setActionId(actionId).build();

    executeCommandTypeMessage.writeDelimitedTo(outputStream);
    executeCommand.writeDelimitedTo(outputStream);
    executeCommandMessage.writeDelimitedTo(outputStream);

    executingActionId = actionId;
    resultEventFuture = SettableFuture.create();
    LOG.info("Started execution of worker tool for for actionId: %s", actionId);

    // TODO : msemko: add timeout/heartbeat, ... ?
    return resultEventFuture.get();
  }

  /** Shuts down launched worker tool. */
  public void shutdown() throws InterruptedException, IOException {
    try {
      CommandTypeMessage shutdownCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.SHUTDOWN_COMMAND);
      shutdownCommandTypeMessage.writeDelimitedTo(outputStream);
      ShutdownCommand shutdownCommand = ShutdownCommand.getDefaultInstance();
      shutdownCommand.writeDelimitedTo(outputStream);
    } finally {
      shutdownFutureIfNotDone();
      waitTillLaunchedProcessFinish();
      closeNamedPipe();
    }
  }

  private void shutdownFutureIfNotDone() {
    if (resultEventFuture != null && !resultEventFuture.isDone()) {
      resultEventFuture.setException(new IllegalStateException("No ResultEvent was received"));
    }
  }

  private void waitTillLaunchedProcessFinish() throws InterruptedException {
    ProcessExecutor.Result executionResult =
        downwardApiProcessExecutor.execute(
            launchedProcess,
            ImmutableSet.<ProcessExecutor.Option>builder()
                .add(ProcessExecutor.Option.EXPECTING_STD_OUT)
                .add(ProcessExecutor.Option.EXPECTING_STD_ERR)
                .build(),
            Optional.empty(),
            Optional.of(SHUTDOWN_TIMEOUT_UNIT.toMillis(SHUTDOWN_TIMEOUT)),
            Optional.of(
                process ->
                    LOG.error(
                        "Timeout while waiting for a launched worker tool process %s to terminate.",
                        getStartWorkerToolCommand())));
    int exitCode = executionResult.getExitCode();
    if (exitCode != 0) {
      LOG.error(
          "Exit code: %s%n[stdOut]%n%s%n[stdErr]%n%s%n",
          exitCode, executionResult.getStdout().orElse(""), executionResult.getStderr().orElse(""));
    }
  }

  private void closeNamedPipe() {
    String namedPipeName = namedPipeWriter.getName();
    try {
      outputStream.close();
    } catch (IOException e) {
      LOG.error(e, "Cannot close output stream from pipe: %s", namedPipeName);
    }

    try {
      namedPipeWriter.close();
    } catch (IOException e) {
      LOG.error(e, "Cannot close named pipe: %s", namedPipeName);
    }
  }

  /** Returns a command that starts Worker Tool process. */
  public abstract ImmutableList<String> getStartWorkerToolCommand();

  private CommandTypeMessage getCommandTypeMessage(CommandTypeMessage.CommandType commandType) {
    CommandTypeMessage.Builder builder = CommandTypeMessage.newBuilder();
    builder.setCommandType(commandType);
    return builder.build();
  }

  private ImmutableMap<String, String> buildEnvs(String namedPipeName) {
    return ImmutableMap.of(WorkerToolConstants.ENV_COMMAND_PIPE, namedPipeName);
  }
}
