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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.pipeline.CompilationDaemonStep;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.javacd.model.BaseCommandParams;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.BuildTargetValue;
import com.facebook.buck.javacd.model.LibraryPipeliningCommand;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.javacd.model.StartNextPipeliningCommand;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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

  private static final String STEP_NAME = "javacd_pipelining";
  private static final String SCOPE_PREFIX = STEP_NAME + "_command";

  private final JavaCDParams javaCDParams;
  private final PipeliningCommand.Builder builder = PipeliningCommand.newBuilder();
  private final List<AbstractMessage> commands = new ArrayList<>();

  private ImmutableMap<String, SettableFuture<ResultEvent>> actionIdToResultEventMap =
      ImmutableMap.of();
  @Nullable private BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool;
  @Nullable private WorkerToolExecutor workerToolExecutor;
  private final SettableFuture<Unit> done = SettableFuture.create();
  private boolean pipelineExecutionFailed = false;

  public JavaCDPipeliningWorkerToolStep(
      PipelineState pipeliningState,
      boolean hasAnnotationProcessing,
      boolean withDownwardApi,
      BaseCommandParams.SpoolMode spoolMode,
      JavaCDParams javaCDParams) {
    super(STEP_NAME);
    this.javaCDParams = javaCDParams;

    builder
        .getBaseCommandParamsBuilder()
        .setHasAnnotationProcessing(hasAnnotationProcessing)
        .setWithDownwardApi(withDownwardApi)
        .setSpoolMode(spoolMode);
    builder.setPipeliningState(pipeliningState);
  }

  @Override
  public void appendStepWithCommand(AbstractMessage command) {
    commands.add(command);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    StepExecutionResult result = execute(context);
    pipelineExecutionFailed = !result.isSuccess();
    return result;
  }

  private StepExecutionResult execute(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    String actionId = context.getActionId();
    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams);

    IsolatedEventBus eventBus = context.getIsolatedEventBus();
    if (borrowedWorkerTool == null) {
      // the first execution
      Preconditions.checkState(actionIdToResultEventMap.isEmpty());

      try (Scope ignored = PerfEvents.scope(eventBus, SCOPE_PREFIX + "_get_wt")) {
        WorkerProcessPool<WorkerToolExecutor> workerToolPool =
            JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);
        borrowedWorkerTool =
            JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
                workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds(), eventBus);
      }

      try (Scope ignored = PerfEvents.scope(eventBus, SCOPE_PREFIX + "_execution")) {
        try {
          workerToolExecutor = Objects.requireNonNull(borrowedWorkerTool).get();
          actionIdToResultEventMap = startExecution(SCOPE_PREFIX, eventBus);
        } catch (ExecutionException e) {
          return JavaCDWorkerStepUtils.createFailStepExecutionResult(
              launchJavaCDCommand, actionId, e);
        }
      }
    } else {
      try (Scope ignored = PerfEvents.scope(eventBus, SCOPE_PREFIX + "_start_next_command")) {
        startNextPipeliningCommand(actionId, eventBus);
      }
    }

    Future<ResultEvent> resultEventFuture =
        Objects.requireNonNull(
            actionIdToResultEventMap.get(actionId),
            String.format(
                "Cannot find a future for actionId: %s among executing: %s",
                actionId, actionIdToResultEventMap.keySet()));

    try (Scope ignored = PerfEvents.scope(eventBus, SCOPE_PREFIX + "_waiting_for_result")) {
      try {
        LOG.debug("Waiting for the result event associated with action id: %s", actionId);
        ResultEvent resultEvent = resultEventFuture.get();
        return JavaCDWorkerStepUtils.createStepExecutionResult(
            launchJavaCDCommand, resultEvent, actionId);
      } catch (ExecutionException e) {
        return JavaCDWorkerStepUtils.createFailStepExecutionResult(
            launchJavaCDCommand, actionId, e);
      }
    }
  }

  private ImmutableMap<String, SettableFuture<ResultEvent>> startExecution(
      String scopePrefix, IsolatedEventBus eventBus)
      throws IOException, ExecutionException, InterruptedException {
    Preconditions.checkNotNull(workerToolExecutor);

    Preconditions.checkArgument(
        !commands.isEmpty() && commands.size() <= 2, "Commands size must be equal only to 1 or 2");

    ImmutableList<String> actionIds;
    PipeliningCommand pipeliningCommand;

    try (Scope ignored = PerfEvents.scope(eventBus, scopePrefix + "_preparing")) {

      ImmutableList.Builder<String> actionsIdsBuilder = ImmutableList.builder();

      // the first command could be either source-abi or library one
      AbstractMessage command1 = commands.get(0);
      if (command1 instanceof BasePipeliningCommand) {
        BasePipeliningCommand abiCommand = (BasePipeliningCommand) command1;
        builder.setAbiCommand(abiCommand);
        actionsIdsBuilder.add(getActionId(abiCommand.getBuildTargetValue()));
      } else {
        Preconditions.checkState(
            command1 instanceof LibraryPipeliningCommand,
            "The first command must be a library one");
        LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command1;
        builder.clearAbiCommand();
        builder.setLibraryCommand(libraryCommand);
        actionsIdsBuilder.add(
            getActionId(libraryCommand.getBasePipeliningCommand().getBuildTargetValue()));
      }

      // the second command could be only library one
      if (commands.size() > 1) {
        AbstractMessage command2 = commands.get(1);
        Preconditions.checkState(
            command2 instanceof LibraryPipeliningCommand,
            "The second command must be a library one");
        LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command2;
        builder.setLibraryCommand(libraryCommand);
        actionsIdsBuilder.add(
            getActionId(libraryCommand.getBasePipeliningCommand().getBuildTargetValue()));
      }

      actionIds = actionsIdsBuilder.build();
      pipeliningCommand = builder.build();
    }

    ImmutableList<SettableFuture<ResultEvent>> futures;
    try (Scope ignored = PerfEvents.scope(eventBus, scopePrefix + "_invocation")) {
      futures =
          workerToolExecutor.executePipeliningCommand(actionIds, pipeliningCommand, done, eventBus);
    }

    try (Scope ignored = PerfEvents.scope(eventBus, scopePrefix + "_result_map")) {
      ImmutableMap.Builder<String, SettableFuture<ResultEvent>> mapBuilder = ImmutableMap.builder();
      for (int i = 0; i < actionIds.size(); i++) {
        mapBuilder.put(actionIds.get(i), futures.get(i));
      }
      return mapBuilder.build();
    }
  }

  private String getActionId(BuildTargetValue buildTargetValue) {
    return buildTargetValue.getFullyQualifiedName();
  }

  private void startNextPipeliningCommand(String actionId, IsolatedEventBus eventBus)
      throws IOException {
    Preconditions.checkNotNull(workerToolExecutor);
    LOG.debug(
        "Sending start execution next pipelining command (action id: %s) signal to worker tool.",
        actionId);
    StartNextPipeliningCommand startNextPipeliningCommand =
        StartNextPipeliningCommand.newBuilder().setActionId(actionId).build();
    workerToolExecutor.startNextCommand(startNextPipeliningCommand, actionId, eventBus);
  }

  @Override
  public void close() {
    boolean pipelineFinishedSuccessfully =
        !pipelineExecutionFailed
            && actionIdToResultEventMap.values().stream()
                .allMatch(f -> f.isDone() && !f.isCancelled());
    try {
      if (pipelineFinishedSuccessfully) {
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
      closeWorkerTool();
      actionIdToResultEventMap = ImmutableMap.of();
    }
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
      if (resultEventFuture != null && !resultEventFuture.isDone()) {
        resultEventFuture.setException(exceptionSupplier.get());
      }
    }
  }

  private void waitWhileExecutionIsDone() {
    if (!done.isDone()) {
      try {
        done.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn(
            e,
            "Thread that waited for pipelining command %s to finish has been interrupted",
            actionIdToResultEventMap.keySet());
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        Throwables.throwIfUnchecked(cause);
        throw new HumanReadableException(
            cause,
            "Waiting for pipelining command %s to finish has been failed. : %s",
            actionIdToResultEventMap.keySet(),
            cause.getMessage());
      }
    }
  }

  private void closeWorkerTool() {
    if (borrowedWorkerTool != null) {
      borrowedWorkerTool.close();
      borrowedWorkerTool = null;
      workerToolExecutor = null;
    }
  }
}
