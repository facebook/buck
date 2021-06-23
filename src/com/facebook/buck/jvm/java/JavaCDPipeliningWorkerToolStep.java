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

package com.facebook.buck.jvm.java;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.pipeline.CompilationDaemonStep;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.javacd.model.BaseCommandParams;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.LibraryPipeliningCommand;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.model.StartNextPipeliningCommand;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * JavaCD pipelining step that communicate with javacd WT and could execute java compilation
 * pipelining command.
 *
 * <p>Pipelining command contains up to two protobuf commands:
 *
 * <ol>
 *   <li>Only source-abi.jar
 *   <li>Only library.jar
 *   <li>Combined source-abi.jar and library.jar commands. Order is the following: 1. source-abi, 2.
 *       library. commands.
 */
class JavaCDPipeliningWorkerToolStep extends AbstractIsolatedExecutionStep
    implements CompilationDaemonStep {

  private static final Logger LOG = Logger.get(JavaCDPipeliningWorkerToolStep.class);

  private static final int MAX_WAIT_TIME_FOR_PIPELINE_FINISH_EVENT_SECONDS = 1;

  private final JavaCDParams javaCDParams;
  private final boolean runWithoutPool;

  private final PipeliningCommand.Builder builder = PipeliningCommand.newBuilder();
  // using LinkedHashMap implementation to support an order of commands.
  private final Map<ActionId, AbstractMessage> commands = new LinkedHashMap<>();

  private ImmutableMap<ActionId, SettableFuture<ResultEvent>> actionIdToResultEventMap =
      ImmutableMap.of();
  @Nullable private BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool;
  @Nullable private WorkerToolExecutor workerToolExecutor;
  private final SettableFuture<PipelineFinishedEvent> pipelineFinished;
  private final AtomicBoolean pipelineExecutionFailed = new AtomicBoolean(false);
  private boolean closed = false;

  public JavaCDPipeliningWorkerToolStep(
      PipelineState pipeliningState,
      boolean hasAnnotationProcessing,
      boolean withDownwardApi,
      BaseCommandParams.SpoolMode spoolMode,
      JavaCDParams javaCDParams) {
    super("javacd_pipelining");
    this.javaCDParams = javaCDParams;
    this.runWithoutPool = javaCDParams.getWorkerToolPoolSize() == 0;

    builder
        .getBaseCommandParamsBuilder()
        .setHasAnnotationProcessing(hasAnnotationProcessing)
        .setWithDownwardApi(withDownwardApi)
        .setSpoolMode(spoolMode);
    builder.setPipeliningState(pipeliningState);

    pipelineFinished = SettableFuture.create();
    Futures.addCallback(
        pipelineFinished,
        new FutureCallback<PipelineFinishedEvent>() {
          @Override
          public void onSuccess(PipelineFinishedEvent resultEvent) {}

          @Override
          public void onFailure(Throwable t) {
            pipelineExecutionFailed.set(true);
          }
        },
        directExecutor());
  }

  @Override
  public void appendStepWithCommand(ActionId actionId, AbstractMessage command) {
    commands.put(actionId, command);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    StepExecutionResult result = execute(context);
    pipelineExecutionFailed.set(!result.isSuccess());
    return result;
  }

  private StepExecutionResult execute(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    ActionId actionId = context.getActionId();
    LOG.debug("Start execution for action id: %s", actionId);
    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams, context.getRuleCellRoot());

    if (workerToolExecutor == null) {
      // the first execution
      Preconditions.checkState(
          actionIdToResultEventMap.isEmpty(),
          "Some actions are executing at the moment: %s",
          actionIdToResultEventMap.keySet());

      LOG.debug("The first step execution. Obtaining worker tool. Action id: %s", actionId);

      if (runWithoutPool) {
        workerToolExecutor =
            JavaCDWorkerStepUtils.getLaunchedWorker(
                context, launchJavaCDCommand, javaCDParams.isIncludeAllBucksEnvVariables());
      } else {
        WorkerProcessPool<WorkerToolExecutor> workerToolPool =
            JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);
        borrowedWorkerTool =
            JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
                workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds());
        workerToolExecutor = borrowedWorkerTool.get();
      }

      try {
        actionIdToResultEventMap = startExecution(workerToolExecutor);
      } catch (ExecutionException e) {
        return JavaCDWorkerStepUtils.createFailStepExecutionResult(
            launchJavaCDCommand, actionId, e);
      }
    } else {
      startNextPipeliningCommand(actionId, workerToolExecutor);
    }

    Future<ResultEvent> resultEventFuture =
        Objects.requireNonNull(
            actionIdToResultEventMap.get(actionId),
            String.format(
                "Cannot find a future for actionId: %s among executing: %s",
                actionId, actionIdToResultEventMap.keySet()));

    try {
      LOG.debug("Waiting for the result event associated with action id: %s", actionId);
      ResultEvent resultEvent =
          resultEventFuture.get(
              javaCDParams.getMaxWaitForResultTimeoutInSeconds(), TimeUnit.SECONDS);
      LOG.debug(
          "Result event (exit code = %s) for action id: %s has been received",
          resultEvent.getExitCode(), actionId);
      return JavaCDWorkerStepUtils.createStepExecutionResult(
          launchJavaCDCommand, resultEvent, actionId);
    } catch (ExecutionException | TimeoutException e) {
      return JavaCDWorkerStepUtils.createFailStepExecutionResult(launchJavaCDCommand, actionId, e);
    }
  }

  private ImmutableMap<ActionId, SettableFuture<ResultEvent>> startExecution(
      WorkerToolExecutor workerToolExecutor) throws IOException, ExecutionException {
    Preconditions.checkNotNull(workerToolExecutor);

    Preconditions.checkArgument(
        !commands.isEmpty() && commands.size() <= 2, "Commands size must be equal only to 1 or 2");

    ImmutableList<ActionId> actionIds;
    PipeliningCommand pipeliningCommand;

    ImmutableList.Builder<ActionId> actionsIdsBuilder = ImmutableList.builder();

    // the first command could be either source-abi or library one
    Iterator<Map.Entry<ActionId, AbstractMessage>> commandsIterator =
        commands.entrySet().iterator();
    Map.Entry<ActionId, AbstractMessage> command1Entry = commandsIterator.next();
    AbstractMessage command1 = command1Entry.getValue();
    ActionId commands1ActionId = command1Entry.getKey();
    if (command1 instanceof BasePipeliningCommand) {
      BasePipeliningCommand abiCommand = (BasePipeliningCommand) command1;
      builder.setAbiCommand(abiCommand);
    } else {
      Preconditions.checkState(
          command1 instanceof LibraryPipeliningCommand, "The first command must be a library one");
      LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command1;
      builder.clearAbiCommand();
      builder.setLibraryCommand(libraryCommand);
    }
    actionsIdsBuilder.add(commands1ActionId);

    // the second command could be only library one
    if (commandsIterator.hasNext()) {
      Map.Entry<ActionId, AbstractMessage> command2Entry = commandsIterator.next();
      AbstractMessage command2 = command2Entry.getValue();
      ActionId command2ActionId = command2Entry.getKey();
      Preconditions.checkState(
          command2 instanceof LibraryPipeliningCommand, "The second command must be a library one");
      LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command2;
      Preconditions.checkState(
          !builder.hasLibraryCommand(),
          "The first command is set to library one. Could not override it with the second library command.");
      builder.setLibraryCommand(libraryCommand);
      actionsIdsBuilder.add(command2ActionId);
    }

    actionIds = actionsIdsBuilder.build();
    pipeliningCommand = builder.build();

    LOG.debug("Starting execution for action ids: %s", actionIds);
    ImmutableList<SettableFuture<ResultEvent>> futures =
        workerToolExecutor.executePipeliningCommand(actionIds, pipeliningCommand, pipelineFinished);
    ImmutableMap.Builder<ActionId, SettableFuture<ResultEvent>> mapBuilder =
        ImmutableMap.builderWithExpectedSize(actionIds.size());
    for (int i = 0; i < actionIds.size(); i++) {
      SettableFuture<ResultEvent> resultEventSettableFuture = futures.get(i);
      Futures.addCallback(
          resultEventSettableFuture,
          new FutureCallback<ResultEvent>() {
            @Override
            public void onSuccess(ResultEvent resultEvent) {}

            @Override
            public void onFailure(Throwable t) {
              pipelineExecutionFailed.set(true);
            }
          },
          directExecutor());
      mapBuilder.put(actionIds.get(i), resultEventSettableFuture);
    }
    return mapBuilder.build();
  }

  private void startNextPipeliningCommand(ActionId actionId, WorkerToolExecutor workerToolExecutor)
      throws IOException {
    Preconditions.checkNotNull(workerToolExecutor);
    LOG.debug(
        "Sending start execution next pipelining command (action id: %s) signal to worker tool.",
        actionId);
    StartNextPipeliningCommand startNextPipeliningCommand =
        StartNextPipeliningCommand.newBuilder().setActionId(actionId.getValue()).build();
    workerToolExecutor.startNextCommand(startNextPipeliningCommand, actionId);
  }

  @Override
  public void close(boolean force) {
    LOG.debug(
        "Closing pipelining step with force flag = %s. Action ids: %s",
        force, actionIdToResultEventMap.keySet());
    Preconditions.checkState(!closed, "Step has been already closed");
    Preconditions.checkState(!actionIdToResultEventMap.isEmpty(), "No actions are executing.");

    boolean pipelineFinishedSuccessfully = !pipelineExecutionFailed.get() && isNotCancelled();
    try {
      if (pipelineFinishedSuccessfully && !force) {
        verifyAllActionsAreDone();
        LOG.info(
            "Start waiting for pipeline finish event for action ids: %s",
            actionIdToResultEventMap.keySet());
        waitWhileExecutionIsDone();
      } else {
        LOG.info(
            "Closing compilation step without waiting for pipeline finish event. Action ids: %s",
            actionIdToResultEventMap.keySet());
      }
    } finally {
      shutdownResultEventFuturesIfNotDone();
      shutdownPipelineResultEventFutureIfNotDone();
      closeWorkerTool();
      actionIdToResultEventMap = ImmutableMap.of();
      commands.clear();
      builder.clear();
      closed = true;
    }
  }

  private void verifyAllActionsAreDone() {
    for (Map.Entry<ActionId, SettableFuture<ResultEvent>> executingAction :
        actionIdToResultEventMap.entrySet()) {
      SettableFuture<ResultEvent> future = executingAction.getValue();
      if (!future.isDone()) {
        throw new IllegalStateException(
            executingAction.getKey() + " associated future is not yet done");
      }
    }
  }

  private boolean isNotCancelled() {
    return actionIdToResultEventMap.values().stream().noneMatch(Future::isCancelled);
  }

  private void shutdownResultEventFuturesIfNotDone() {
    Supplier<Exception> exceptionSupplier =
        Suppliers.memoize(
            () ->
                new IllegalStateException(
                    String.format(
                        "No result events have been received for action ids: %s",
                        actionIdToResultEventMap.keySet())));
    for (SettableFuture<ResultEvent> resultEventFuture : actionIdToResultEventMap.values()) {
      if (!resultEventFuture.isDone()) {
        resultEventFuture.setException(exceptionSupplier.get());
      }
    }
  }

  private void shutdownPipelineResultEventFutureIfNotDone() {
    Supplier<Exception> exceptionSupplier =
        Suppliers.memoize(
            () ->
                new IllegalStateException(
                    String.format(
                        "No pipeline result event has been received for action ids: %s",
                        actionIdToResultEventMap.keySet())));
    if (!pipelineFinished.isDone()) {
      pipelineFinished.setException(exceptionSupplier.get());
    }
  }

  private void waitWhileExecutionIsDone() {
    if (!pipelineFinished.isDone()) {
      try {
        pipelineFinished.get(MAX_WAIT_TIME_FOR_PIPELINE_FINISH_EVENT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn(
            e,
            "Thread that waited for pipelining command %s to finish has been interrupted",
            actionIdToResultEventMap.keySet());
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        Throwables.throwIfUnchecked(cause);
        throw toHumanReadableException(cause, cause.getMessage());
      } catch (TimeoutException e) {
        throw toHumanReadableException(
            e,
            "timeout waiting for pipelined command of "
                + MAX_WAIT_TIME_FOR_PIPELINE_FINISH_EVENT_SECONDS
                + " seconds elapsed");
      }
    }
  }

  private HumanReadableException toHumanReadableException(Throwable t, String message) {
    return new HumanReadableException(
        t,
        "Waiting for pipelining command %s to finish has failed. : %s",
        actionIdToResultEventMap.keySet(),
        message);
  }

  private void closeWorkerTool() {
    if (runWithoutPool) {
      reallyCloseWorkerTool();
    } else {
      returnWorkerToolBackToPool();
    }
  }

  private void reallyCloseWorkerTool() {
    if (workerToolExecutor != null) {
      workerToolExecutor.close();
      workerToolExecutor = null;
    }
  }

  private void returnWorkerToolBackToPool() {
    if (borrowedWorkerTool != null) {
      borrowedWorkerTool.close();
      borrowedWorkerTool = null;
      workerToolExecutor = null;
    }
  }
}
