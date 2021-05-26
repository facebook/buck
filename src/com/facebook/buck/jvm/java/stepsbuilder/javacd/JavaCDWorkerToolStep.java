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

package com.facebook.buck.jvm.java.stepsbuilder.javacd;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.JavaCDWorkerStepUtils;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.util.Scope;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private static final Logger LOG = Logger.get(JavaCDWorkerToolStep.class);

  private static final String STEP_NAME = "javacd";
  private static final String SCOPE_PREFIX = STEP_NAME + "_command";

  private final JavaCDParams javaCDParams;
  private final BuildJavaCommand buildJavaCommand;

  public JavaCDWorkerToolStep(BuildJavaCommand buildJavaCommand, JavaCDParams javaCDParams) {
    super(STEP_NAME);
    this.buildJavaCommand = buildJavaCommand;
    this.javaCDParams = javaCDParams;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    IsolatedEventBus eventBus = context.getIsolatedEventBus();

    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams);

    WorkerProcessPool<WorkerToolExecutor> workerToolPool =
        JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);

    BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool = null;
    try {
      try (Scope ignored =
          PerfEvents.scope(eventBus, context.getActionId(), SCOPE_PREFIX + "_get_wt")) {
        borrowedWorkerTool =
            JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
                workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds());
      }

      return executeBuildJavaCommand(context, borrowedWorkerTool.get(), launchJavaCDCommand);

    } finally {
      if (borrowedWorkerTool != null) {
        borrowedWorkerTool.close();
      }
    }
  }

  private StepExecutionResult executeBuildJavaCommand(
      IsolatedExecutionContext context,
      WorkerToolExecutor workerToolExecutor,
      ImmutableList<String> launchJavaCDCommand)
      throws IOException, InterruptedException {
    String actionId = context.getActionId();
    IsolatedEventBus eventBus = context.getIsolatedEventBus();
    try {
      LOG.debug("Starting execution of java compilation command with action id: %s", actionId);
      SettableFuture<ResultEvent> resultEventFuture;
      try (Scope ignored = PerfEvents.scope(eventBus, actionId, SCOPE_PREFIX + "_execution")) {
        resultEventFuture = workerToolExecutor.executeCommand(actionId, buildJavaCommand, eventBus);
      }

      ResultEvent resultEvent;
      try (Scope ignored =
          PerfEvents.scope(eventBus, actionId, SCOPE_PREFIX + "_waiting_for_result")) {
        resultEvent = resultEventFuture.get();
      }

      return JavaCDWorkerStepUtils.createStepExecutionResult(
          launchJavaCDCommand, resultEvent, actionId);
    } catch (ExecutionException e) {
      return JavaCDWorkerStepUtils.createFailStepExecutionResult(launchJavaCDCommand, actionId, e);
    }
  }
}
