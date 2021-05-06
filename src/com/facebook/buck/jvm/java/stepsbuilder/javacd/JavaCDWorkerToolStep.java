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
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private final JavaCDParams javaCDParams;
  private final BuildJavaCommand buildJavaCommand;

  public JavaCDWorkerToolStep(BuildJavaCommand buildJavaCommand, JavaCDParams javaCDParams) {
    super("javacd_wt");
    this.buildJavaCommand = buildJavaCommand;
    this.javaCDParams = javaCDParams;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams);

    WorkerProcessPool<WorkerToolExecutor> workerToolPool =
        JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);

    try (BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool =
        JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
            workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds())) {
      WorkerToolExecutor workerToolExecutor = borrowedWorkerTool.get();
      return executeBuildJavaCommand(context, workerToolExecutor, launchJavaCDCommand);
    }
  }

  private StepExecutionResult executeBuildJavaCommand(
      IsolatedExecutionContext context,
      WorkerToolExecutor workerToolExecutor,
      ImmutableList<String> launchJavaCDCommand)
      throws IOException, InterruptedException {
    String actionId = context.getActionId();
    try {
      ResultEvent resultEvent = workerToolExecutor.executeCommand(actionId, buildJavaCommand);
      return JavaCDWorkerStepUtils.createStepExecutionResult(
          launchJavaCDCommand, resultEvent, actionId);
    } catch (ExecutionException e) {
      return JavaCDWorkerStepUtils.createFailStepExecutionResult(launchJavaCDCommand, actionId, e);
    }
  }
}
