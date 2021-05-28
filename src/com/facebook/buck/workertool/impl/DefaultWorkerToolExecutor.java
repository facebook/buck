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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.processexecutor.DefaultNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiLaunchedProcess;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.facebook.buck.workertool.model.StartPipelineCommand;
import com.facebook.buck.workertool.utils.WorkerToolConstants;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Default implementation of {@link WorkerToolExecutor} */
public class DefaultWorkerToolExecutor implements WorkerToolExecutor {

  private static final Logger LOG = Logger.get(DefaultWorkerToolExecutor.class);

  private static final String EXECUTE_WT_COMMAND_SCOPE_PREFIX = "execute_wt_command";
  private static final String EXECUTE_WT_PIPELINING_COMMAND_SCOPE_PREFIX =
      "execute_pipelining_wt_command";
  private static final String START_NEXT_PIPELINING_WT_COMMAND_SCOPE_PREFIX =
      "start_next_pipelining_wt_command";

  private static final AtomicInteger COUNTER = new AtomicInteger();

  private static final long WAIT_FOR_PROCESS_SHUTDOWN_TIMEOUT = 2;
  private static final TimeUnit WAIT_FOR_PROCESS_SHUTDOWN_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();

  private final int workerId;
  private final DownwardApiProcessExecutor downwardApiProcessExecutor;
  private final ImmutableList<String> startWorkerToolCommand;

  private final NamedPipeWriter namedPipeWriter;
  private final OutputStream outputStream;
  private final DownwardApiLaunchedProcess launchedProcess;

  private final ExecutorService workerToolProcessMonitorExecutor;
  private final Future<?> waitForLaunchedProcessFuture;

  private ImmutableList<ExecutingAction> executingActions = ImmutableList.of();
  @Nullable private SettableFuture<PipelineFinishedEvent> pipelineFinished;

  /** Holds execution action details */
  @BuckStyleValue
  abstract static class ExecutingAction {

    abstract ActionId getActionId();

    abstract SettableFuture<ResultEvent> getResultEventFuture();

    public static ExecutingAction of(ActionId actionId) {
      return ImmutableExecutingAction.ofImpl(actionId, SettableFuture.create());
    }
  }

  DefaultWorkerToolExecutor(
      IsolatedExecutionContext context,
      ImmutableList<String> startWorkerToolCommand,
      ImmutableMap<String, String> envs)
      throws IOException {
    this.workerId = COUNTER.incrementAndGet();
    this.downwardApiProcessExecutor =
        context.getDownwardApiProcessExecutor(WorkerToolExecutorNamedPipeEventHandler::new);
    this.startWorkerToolCommand = startWorkerToolCommand;

    boolean launched = false;
    try {
      this.namedPipeWriter = NAMED_PIPE_FACTORY.createAsWriter();

      this.launchedProcess =
          downwardApiProcessExecutor.launchProcess(
              ProcessExecutorParams.builder()
                  .addAllCommand(startWorkerToolCommand)
                  .setEnvironment(buildEnvs(envs, namedPipeWriter.getName()))
                  .build());
      launched = true;
    } catch (IOException e) {
      throw new IOException(
          String.format("Cannot launch a new worker tool process %s", startWorkerToolCommand), e);
    } finally {
      if (!launched) {
        closeNamedPipe();
      }
    }
    this.workerToolProcessMonitorExecutor =
        MostExecutors.newSingleThreadExecutor("WorkerToolProcessMonitor_" + workerId);
    this.waitForLaunchedProcessFuture =
        workerToolProcessMonitorExecutor.submit(
            () -> {
              boolean finishedSuccessfully =
                  waitTillLaunchedProcessFinish(WaitOption.INDEFINITELY)
                      .map(this::isFinishedSuccessfully)
                      .orElse(true);
              if (!finishedSuccessfully) {
                String errorMessage =
                    String.format(
                        "Worker tool process: %s has been terminated. Worker id: %s",
                        startWorkerToolCommand, workerId);
                LOG.warn(errorMessage);
                shutdownResultEventFutureIfNotDone(errorMessage);
                closeNamedPipe();
              }
            });

    boolean streamOpened = false;
    try {
      this.outputStream = DefaultWorkerToolUtils.openStreamFromNamedPipe(namedPipeWriter, workerId);
      streamOpened = true;
    } finally {
      if (!streamOpened) {
        closeNamedPipe();
      }
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
      } else if (eventType == EventType.PIPELINE_FINISHED_EVENT) {
        processPipelineFinishedEvent((PipelineFinishedEvent) event);
      } else {
        super.processEvent(eventType, event);
      }
    }

    private void processResultEvent(ResultEvent resultEvent) {
      LOG.debug(
          "Received result event for action id: %s, worker id: %s",
          resultEvent.getActionId(), workerId);
      receiveResultEvent(resultEvent);
    }

    private void processPipelineFinishedEvent(PipelineFinishedEvent pipelineFinishedEvent) {
      runUnderLock(
          () -> {
            String actionId =
                Objects.requireNonNull(
                    pipelineFinishedEvent.getActionId(), "action id has to be set");
            LOG.debug(
                "Received pipeline finished event with action id: %s. Actions: %s, worker id: %s",
                actionId, getExecutingActionIds(), workerId);

            // signal to Step that pipeline is finished
            checkNotNull(pipelineFinished);
            pipelineFinished.set(pipelineFinishedEvent);
          });
    }
  }

  @Override
  public SettableFuture<ResultEvent> executeCommand(
      ActionId actionId, AbstractMessage executeCommandMessage, IsolatedEventBus eventBus)
      throws IOException {
    checkState(isAlive(), "Launched process is not alive");
    launchedProcess.registerActionId(actionId);

    CommandTypeMessage executeCommandTypeMessage;
    ExecuteCommand executeCommand;
    AtomicReference<ExecutingAction> executingActionReference = new AtomicReference<>();

    try (Scope ignored =
        PerfEvents.scope(eventBus, actionId, EXECUTE_WT_COMMAND_SCOPE_PREFIX + "_preparing")) {
      runUnderLock(
          () -> {
            checkThatNoActionsAreExecuting();
            ExecutingAction executingAction = ExecutingAction.of(actionId);
            executingActionReference.set(executingAction);
            executingActions = ImmutableList.of(executingAction);
          });

      executeCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.EXECUTE_COMMAND);
      executeCommand = ExecuteCommand.newBuilder().setActionId(actionId.getValue()).build();
    }

    try (Scope ignored =
        PerfEvents.scope(eventBus, actionId, EXECUTE_WT_COMMAND_SCOPE_PREFIX + "_write")) {
      executeCommandTypeMessage.writeDelimitedTo(outputStream);
      executeCommand.writeDelimitedTo(outputStream);
      executeCommandMessage.writeDelimitedTo(outputStream);
    }

    LOG.debug(
        "Started execution of worker tool for for actionId: %s, worker id: %s", actionId, workerId);
    return executingActionReference.get().getResultEventFuture();
  }

  @Override
  public ImmutableList<SettableFuture<ResultEvent>> executePipeliningCommand(
      ImmutableList<ActionId> actionIds,
      AbstractMessage pipeliningCommand,
      SettableFuture<PipelineFinishedEvent> pipelineFinished,
      IsolatedEventBus eventBus)
      throws IOException {
    checkState(isAlive(), "Launched process is not alive");
    actionIds.forEach(launchedProcess::registerActionId);

    ActionId firstActionId = actionIds.iterator().next();

    CommandTypeMessage executeCommandTypeMessage;
    StartPipelineCommand startPipelineCommand;
    try (Scope ignored =
        PerfEvents.scope(
            eventBus, firstActionId, EXECUTE_WT_PIPELINING_COMMAND_SCOPE_PREFIX + "_preparing")) {
      StartPipelineCommand.Builder startPipeliningCommandBuilder =
          StartPipelineCommand.newBuilder();
      runUnderLock(
          () -> {
            checkThatNoActionsAreExecuting();

            ImmutableList.Builder<ExecutingAction> executingActionBuilder =
                ImmutableList.builderWithExpectedSize(actionIds.size());
            for (ActionId actionId : actionIds) {
              executingActionBuilder.add(ExecutingAction.of(actionId));
              startPipeliningCommandBuilder.addActionId(actionId.getValue());
            }
            executingActions = executingActionBuilder.build();
            this.pipelineFinished = pipelineFinished;
          });

      executeCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.START_PIPELINE_COMMAND);
      startPipelineCommand = startPipeliningCommandBuilder.build();
    }

    try (Scope ignored =
        PerfEvents.scope(
            eventBus, firstActionId, EXECUTE_WT_PIPELINING_COMMAND_SCOPE_PREFIX + "_write")) {
      executeCommandTypeMessage.writeDelimitedTo(outputStream);
      startPipelineCommand.writeDelimitedTo(outputStream);
      pipeliningCommand.writeDelimitedTo(outputStream);
    }

    LOG.debug(
        "Started execution of worker tool for for pipelining actionIds: %s, worker id: %s",
        actionIds, workerId);
    return executingActions.stream()
        .map(ExecutingAction::getResultEventFuture)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public void startNextCommand(
      AbstractMessage startNextPipeliningCommand, ActionId actionId, IsolatedEventBus eventBus)
      throws IOException {
    checkState(isAlive(), "Launched process is not alive");

    CommandTypeMessage commandTypeMessage;
    try (Scope ignored =
        PerfEvents.scope(
            eventBus, actionId, START_NEXT_PIPELINING_WT_COMMAND_SCOPE_PREFIX + "_preparing")) {
      runUnderLock(
          () -> {
            checkNotNull(pipelineFinished, "Pipeline is not started.");
            checkState(!pipelineFinished.isDone(), "Pipeline is finished.");
            boolean isExecuting = false;
            for (ExecutingAction executingAction : executingActions) {
              if (executingAction.getActionId().equals(actionId)) {
                isExecuting = true;
                break;
              }
            }
            checkState(
                isExecuting,
                "Action id: %s is not found among currently execution actions: %s",
                actionId,
                getExecutingActionIds());
          });

      commandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.START_NEXT_PIPELINING_COMMAND);
    }

    try (Scope ignored =
        PerfEvents.scope(
            eventBus, actionId, START_NEXT_PIPELINING_WT_COMMAND_SCOPE_PREFIX + "_write")) {
      commandTypeMessage.writeDelimitedTo(outputStream);
      startNextPipeliningCommand.writeDelimitedTo(outputStream);
    }

    LOG.debug(
        "Started next pipelining command with action id: %s has been send to worker id: %s",
        actionId, workerId);
  }

  private void checkThatNoActionsAreExecuting() {
    // `executingActions` list has to be empty when a request to execute a new command arrived.
    checkState(executingActions.isEmpty(), "Actions %s are executing...", getExecutingActionIds());
  }

  private void prepareForTheNextCommand() {
    runUnderLock(
        () -> {
          verifyAllActionsAreDone();
          // Set `executingActions` to an empty list that signals that new command could be
          // executed.
          executingActions = ImmutableList.of();
          pipelineFinished = null;
        });
  }

  private void verifyAllActionsAreDone() {
    for (ExecutingAction executingAction : executingActions) {
      SettableFuture<ResultEvent> future = executingAction.getResultEventFuture();
      if (!future.isDone()) {
        throw new IllegalStateException(
            executingAction.getActionId() + " associated future is not yet done");
      }
    }
  }

  /**
   * Entry point to {@link WorkerToolExecutorNamedPipeEventHandler} to signal that {@link
   * ResultEvent} is received.
   */
  private void receiveResultEvent(ResultEvent resultEvent) {
    runUnderLock(
        () -> {
          // `executingActions` has to be not empty that signals that
          // executor is waiting for a result event from a launched process.
          checkState(!executingActions.isEmpty(), "The is no action executing at the moment.");

          String actionId = resultEvent.getActionId();

          ExecutingAction executingAction =
              executingActions.stream()
                  .filter(action -> !action.getResultEventFuture().isDone())
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "The is no not completed executing action at the moment."));

          ActionId executingActionId = executingAction.getActionId();
          checkState(
              actionId.equals(executingActionId.getValue()),
              "Received action id %s is not equals to expected one %s. Currently executing actions: %s",
              actionId,
              executingActionId,
              getExecutingActionIds());

          SettableFuture<ResultEvent> resultEventFuture = executingAction.getResultEventFuture();
          resultEventFuture.set(resultEvent);
        });
  }

  private String getExecutingActionIds() {
    return executingActions.stream()
        .map(ExecutingAction::getActionId)
        .map(ActionId::getValue)
        .collect(Collectors.joining(", "));
  }

  @Override
  public void close() {
    try {
      if (isAlive()) {
        shutdownLaunchedProcess();
      }
    } finally {
      shutdownWaitForLaunchedProcessFuture();
      shutdownResultEventFutureIfNotDone("No ResultEvent was received");
      closeNamedPipe();
    }
  }

  private void shutdownLaunchedProcess() {
    sendShutdownCommand();
    waitTillLaunchedProcessFinish(WaitOption.USE_TIMEOUT)
        .ifPresent(
            exitCode -> {
              if (!isFinishedSuccessfully(exitCode)) {
                LOG.warn(
                    "Worker tool process %s exit code: %s. Worker id: %s",
                    startWorkerToolCommand, exitCode, workerId);
              }
            });
  }

  @VisibleForTesting
  public void sendShutdownCommand() {
    LOG.debug("Sending shutdown command to worker tool: %s", workerId);
    try {
      CommandTypeMessage shutdownCommandTypeMessage =
          getCommandTypeMessage(CommandTypeMessage.CommandType.SHUTDOWN_COMMAND);
      shutdownCommandTypeMessage.writeDelimitedTo(outputStream);
      ShutdownCommand shutdownCommand = ShutdownCommand.getDefaultInstance();
      shutdownCommand.writeDelimitedTo(outputStream);
    } catch (IOException e) {
      LOG.error(
          e,
          "Cannot write shutdown command for named pipe: %s. Worker id: %s",
          namedPipeWriter.getName(),
          workerId);
    }
  }

  private boolean isFinishedSuccessfully(int exitCode) {
    return exitCode == 0;
  }

  private void shutdownResultEventFutureIfNotDone(String errorMessage) {
    Supplier<Exception> exceptionSupplier =
        Suppliers.memoize(() -> new IllegalStateException(errorMessage));

    runUnderLock(
        () -> {
          for (ExecutingAction executingAction : executingActions) {
            SettableFuture<ResultEvent> resultEventFuture = executingAction.getResultEventFuture();
            if (!resultEventFuture.isDone()) {
              resultEventFuture.setException(exceptionSupplier.get());
            }
          }
          if (pipelineFinished != null) {
            pipelineFinished.setException(exceptionSupplier.get());
          }
        });
  }

  private void shutdownWaitForLaunchedProcessFuture() {
    if (waitForLaunchedProcessFuture != null && !waitForLaunchedProcessFuture.isDone()) {
      waitForLaunchedProcessFuture.cancel(true);
    }
    DefaultWorkerToolUtils.shutdownExecutor(workerToolProcessMonitorExecutor);
  }

  private enum WaitOption {
    USE_TIMEOUT,
    INDEFINITELY,
  }

  /** @return {@link Optional} exit code of the process. */
  private Optional<Integer> waitTillLaunchedProcessFinish(WaitOption waitOption) {

    Optional<Long> timeOutMs;
    Optional<Consumer<Process>> timeOutHandler;

    switch (waitOption) {
      case USE_TIMEOUT:
        timeOutMs =
            Optional.of(
                WAIT_FOR_PROCESS_SHUTDOWN_TIMEOUT_UNIT.toMillis(WAIT_FOR_PROCESS_SHUTDOWN_TIMEOUT));
        timeOutHandler =
            Optional.of(
                process ->
                    LOG.error(
                        "Timeout while waiting for a launched worker tool process %s to terminate. Worker id: %s",
                        startWorkerToolCommand, workerId));
        break;

      case INDEFINITELY:
        timeOutMs = Optional.empty();
        timeOutHandler = Optional.empty();
        break;

      default:
        throw new IllegalStateException(waitOption + " is not supported!");
    }

    try {
      ProcessExecutor.Result executionResult =
          downwardApiProcessExecutor.execute(
              launchedProcess,
              ImmutableSet.<ProcessExecutor.Option>builder()
                  .add(ProcessExecutor.Option.EXPECTING_STD_OUT)
                  .add(ProcessExecutor.Option.EXPECTING_STD_ERR)
                  .build(),
              Optional.empty(),
              timeOutMs,
              timeOutHandler);

      int exitCode = executionResult.getExitCode();

      if (exitCode != 0) {
        LOG.warn(
            "Worker tool process %s exit code: %s%n Std out: %s%n Std err: %s%nWorker id: %s",
            startWorkerToolCommand,
            exitCode,
            executionResult.getStdout(),
            executionResult.getStderr(),
            workerId);
      } else {
        LOG.debug(
            "Worker tool process %s finished successfully. Worker id: %s",
            startWorkerToolCommand, workerId);
      }
      return Optional.of(exitCode);

    } catch (InterruptedException e) {
      LOG.warn(
          "The current thread is interrupted by another thread while it is waiting for a launched process %s to finish. Worker id: %s",
          startWorkerToolCommand, workerId);
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
      LOG.error(
          e, "Cannot close output stream from pipe: %s. Worker id: %s", namedPipeName, workerId);
    }

    try {
      namedPipeWriter.close();
    } catch (IOException e) {
      LOG.error(e, "Cannot close named pipe: %s. Worker id: %s", namedPipeName, workerId);
    }
  }

  private CommandTypeMessage getCommandTypeMessage(CommandTypeMessage.CommandType commandType) {
    CommandTypeMessage.Builder builder = CommandTypeMessage.newBuilder();
    builder.setCommandType(commandType);
    return builder.build();
  }

  private ImmutableMap<String, String> buildEnvs(
      ImmutableMap<String, String> envs, String namedPipeName) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    builder.putAll(envs);
    builder.put(WorkerToolConstants.ENV_COMMAND_PIPE, namedPipeName);
    return builder.build();
  }

  private void runUnderLock(Runnable runnable) {
    synchronized (this) {
      runnable.run();
    }
  }

  @Override
  public void prepareForReuse() {
    prepareForTheNextCommand();
    launchedProcess.prepareForReuse();
  }

  @Override
  public boolean isAlive() {
    return launchedProcess != null && launchedProcess.isAlive();
  }
}
