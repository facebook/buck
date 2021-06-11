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
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.JavaCDWorkerStepUtils;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private static final Logger LOG = Logger.get(JavaCDWorkerToolStep.class);

  private final BuildJavaCommand buildJavaCommand;
  private final JavaCDParams javaCDParams;
  private final boolean runWithoutPool;

  public JavaCDWorkerToolStep(BuildJavaCommand buildJavaCommand, JavaCDParams javaCDParams) {
    super("javacd");
    this.buildJavaCommand = buildJavaCommand;
    this.javaCDParams = javaCDParams;
    this.runWithoutPool = javaCDParams.getWorkerToolPoolSize() == 0;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams, context.getRuleCellRoot());

    if (runWithoutPool) {
      try (WorkerToolExecutor workerToolExecutor =
          JavaCDWorkerStepUtils.getLaunchedWorker(context, launchJavaCDCommand)) {
        return executeBuildJavaCommand(context, workerToolExecutor, launchJavaCDCommand);
      }
    }

    WorkerProcessPool<WorkerToolExecutor> workerToolPool =
        JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);
    return executeOnWorkerToolPool(context, launchJavaCDCommand, workerToolPool);
  }

  private StepExecutionResult executeOnWorkerToolPool(
      IsolatedExecutionContext context,
      ImmutableList<String> launchJavaCDCommand,
      WorkerProcessPool<WorkerToolExecutor> workerToolPool)
      throws InterruptedException, IOException {

    try (BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool =
        JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
            workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds())) {
      return executeBuildJavaCommand(context, borrowedWorkerTool.get(), launchJavaCDCommand);
    }
  }

  private StepExecutionResult executeBuildJavaCommand(
      IsolatedExecutionContext context,
      WorkerToolExecutor workerToolExecutor,
      ImmutableList<String> launchJavaCDCommand)
      throws IOException, InterruptedException {
    ActionId actionId = context.getActionId();
    try {
      LOG.debug("Starting execution of java compilation command with action id: %s", actionId);
      SettableFuture<ResultEvent> resultEventFuture =
          workerToolExecutor.executeCommand(actionId, buildJavaCommand);
      ResultEvent resultEvent =
          resultEventFuture.get(
              javaCDParams.getMaxWaitForResultTimeoutInSeconds(), TimeUnit.SECONDS);
      return JavaCDWorkerStepUtils.createStepExecutionResult(
          launchJavaCDCommand, resultEvent, actionId);
    } catch (ExecutionException | TimeoutException e) {
      return JavaCDWorkerStepUtils.createFailStepExecutionResult(launchJavaCDCommand, actionId, e);
    }
  }
}
