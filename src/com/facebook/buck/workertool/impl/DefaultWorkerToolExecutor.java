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

package com.facebook.buck.workertool.impl;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.processexecutor.DefaultNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiLaunchedProcess;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.workertool.WorkerToolExecutor;
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

/** Default implementation of {@link WorkerToolExecutor} */
class DefaultWorkerToolExecutor implements WorkerToolExecutor {

  private static final Logger LOG = Logger.get(DefaultWorkerToolExecutor.class);

  private static final long SHUTDOWN_TIMEOUT = 2;
  private static final TimeUnit SHUTDOWN_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();

  private final DownwardApiProcessExecutor downwardApiProcessExecutor;
  private final ImmutableList<String> startWorkerToolCommand;

  private final NamedPipeWriter namedPipeWriter;
  private final OutputStream outputStream;
  private final DownwardApiLaunchedProcess launchedProcess;

  @Nullable private volatile String executingActionId;
  @Nullable private volatile SettableFuture<ResultEvent> resultEventFuture;

  DefaultWorkerToolExecutor(
      IsolatedExecutionContext context, ImmutableList<String> startWorkerToolCommand)
      throws IOException {
    this.downwardApiProcessExecutor =
        context.getDownwardApiProcessExecutor(WorkerToolExecutorNamedPipeEventHandler::new);
    this.startWorkerToolCommand = startWorkerToolCommand;

    try {
      this.namedPipeWriter = NAMED_PIPE_FACTORY.createAsWriter();
      this.outputStream = namedPipeWriter.getOutputStream();
      this.launchedProcess =
          downwardApiProcessExecutor.launchProcess(
              ProcessExecutorParams.builder()
                  .addAllCommand(startWorkerToolCommand)
                  .setEnvironment(buildEnvs(namedPipeWriter.getName()))
                  .build());
    } catch (IOException e) {
      closeNamedPipe();
      throw new IOException(
          String.format("Can't launch a new worker tool process %s", startWorkerToolCommand), e);
    }
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
      LOG.info("Received result event for action id: %s", resultEvent.getActionId());
      receiveResultEvent(resultEvent);
    }
  }

  @Override
  public ResultEvent executeCommand(String actionId, AbstractMessage executeCommandMessage)
      throws IOException, ExecutionException, InterruptedException {
    // `executingActionId` and `resultEventFuture` have to be null when a request to execute a new
    // command arrived.
    Preconditions.checkState(
        executingActionId == null, "Action with id" + executingActionId + " is executing");
    executingActionId = actionId;
    Preconditions.checkState(resultEventFuture == null, "Result event future is not null");
    resultEventFuture = SettableFuture.create();

    CommandTypeMessage executeCommandTypeMessage =
        getCommandTypeMessage(CommandTypeMessage.CommandType.EXECUTE_COMMAND);
    ExecuteCommand executeCommand = ExecuteCommand.newBuilder().setActionId(actionId).build();

    executeCommandTypeMessage.writeDelimitedTo(outputStream);
    executeCommand.writeDelimitedTo(outputStream);
    executeCommandMessage.writeDelimitedTo(outputStream);

    LOG.info("Started execution of worker tool for for actionId: %s", actionId);

    // TODO : msemko: add timeout/heartbeat, ... ?
    ResultEvent resultEvent = resultEventFuture.get();
    // After receiving the result from `WorkerToolExecutorNamedPipeEventHandler` set
    // `executingActionId` and `resultEventFuture` to null that signals that new command could be
    // executed.
    resultEventFuture = null;
    executingActionId = null;
    return resultEvent;
  }

  /**
   * Entry point to {@link WorkerToolExecutorNamedPipeEventHandler} to signal that {@link
   * ResultEvent} is received.
   */
  private void receiveResultEvent(ResultEvent resultEvent) {
    // `executingActionId` and `resultEventFuture` have to be not null that signals that executor is
    // waiting for a result event from a launched process.
    Preconditions.checkNotNull(executingActionId, "The is no action executing at the moment.");
    Preconditions.checkNotNull(resultEventFuture, "Result event future is null");

    String actionId = resultEvent.getActionId();
    Preconditions.checkState(
        actionId.equals(executingActionId),
        String.format(
            "Received action id %s is not equals to expected one %s", actionId, executingActionId));
    resultEventFuture.set(resultEvent);
  }

  @Override
  public void close() {
    try {
      shutdownLaunchedProcess();
    } finally {
      shutdownResultEventFutureIfNotDone("No ResultEvent was received");
      closeNamedPipe();
    }
  }

  private void shutdownLaunchedProcess() {
    sendShutdownCommand();
    waitWithTimeoutTillLaunchedProcessFinished();
  }

  private void sendShutdownCommand() {
    try {
      CommandTypeMessage shutdownCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.SHUTDOWN_COMMAND);
      shutdownCommandTypeMessage.writeDelimitedTo(outputStream);
      ShutdownCommand shutdownCommand = ShutdownCommand.getDefaultInstance();
      shutdownCommand.writeDelimitedTo(outputStream);
    } catch (IOException e) {
      LOG.error(
          e, "Cannot write shutdown command for for named pipe: %s", namedPipeWriter.getName());
    }
  }

  private void waitWithTimeoutTillLaunchedProcessFinished() {
    waitTillLaunchedProcessFinish()
        .ifPresent(
            exitCode -> {
              if (!isFinishedSuccessfully(exitCode)) {
                LOG.warn("Worker tool process %s exit code: %s", startWorkerToolCommand, exitCode);
              }
            });
  }

  private boolean isFinishedSuccessfully(int exitCode) {
    return exitCode == 0;
  }

  private void shutdownResultEventFutureIfNotDone(String errorMessage) {
    if (resultEventFuture != null && !resultEventFuture.isDone()) {
      resultEventFuture.setException(new IllegalStateException(errorMessage));
    }
  }

  /** @return {@link Optional} exit code of the process. */
  private Optional<Integer> waitTillLaunchedProcessFinish() {
    try {
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
                          startWorkerToolCommand)));

      int exitCode = executionResult.getExitCode();

      LOG.info(
          "Worker tool process %s exit code: %s%n Std out: %s%n Std err: %s",
          startWorkerToolCommand,
          exitCode,
          executionResult.getStdout(),
          executionResult.getStderr());
      return Optional.of(exitCode);

    } catch (InterruptedException e) {
      LOG.warn(
          "The current thread is interrupted by another thread while it is waiting for a launched process %s to finish.",
          startWorkerToolCommand);
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private void closeNamedPipe() {
    if (namedPipeWriter == null) {
      return;
    }

    String namedPipeName = namedPipeWriter.getName();
    try {
      if (outputStream != null) {
        outputStream.close();
      }
    } catch (IOException e) {
      LOG.error(e, "Cannot close output stream from pipe: %s", namedPipeName);
    }

    try {
      namedPipeWriter.close();
    } catch (IOException e) {
      LOG.error(e, "Cannot close named pipe: %s", namedPipeName);
    }
  }

  private CommandTypeMessage getCommandTypeMessage(CommandTypeMessage.CommandType commandType) {
    CommandTypeMessage.Builder builder = CommandTypeMessage.newBuilder();
    builder.setCommandType(commandType);
    return builder.build();
  }

  private ImmutableMap<String, String> buildEnvs(String namedPipeName) {
    return ImmutableMap.of(WorkerToolConstants.ENV_COMMAND_PIPE, namedPipeName);
  }

  @Override
  public void prepareForReuse() {
    launchedProcess.updateThreadId();
  }

  @Override
  public boolean isAlive() {
    return launchedProcess != null && launchedProcess.isAlive();
  }
}
