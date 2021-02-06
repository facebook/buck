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
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.impl.BaseWorkerToolExecutor;
import com.facebook.buck.workertool.impl.WorkerToolPoolFactory;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private static final String JAVACD_ENV_VARIABLE = "buck.javacd";

  // TODO : msemko : make pool size configurable. Introduce configuration properties
  private static final int POOL_CAPACITY =
      (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.75);

  private final BuildJavaCommand buildJavaCommand;
  private final ImmutableList<String> launchJavaCDCommand;

  public JavaCDWorkerToolStep(
      BuildJavaCommand buildJavaCommand, ImmutableList<String> javaRuntimeLauncherCommand) {
    super("javacd_wt");
    this.buildJavaCommand = buildJavaCommand;
    this.launchJavaCDCommand = getLaunchJavaCDCommand(javaRuntimeLauncherCommand);
  }

  private static ImmutableList<String> getLaunchJavaCDCommand(
      ImmutableList<String> javaRuntimeLauncherCommand) {
    int runArgumentsCount = 3;
    return ImmutableList.<String>builderWithExpectedSize(
            javaRuntimeLauncherCommand.size() + runArgumentsCount)
        .addAll(javaRuntimeLauncherCommand)
        // TODO : msemko : make javacd JVM args configurable. Introduce configuration properties
        .add("-Dfile.encoding=" + StandardCharsets.UTF_8.name())
        .add("-jar")
        .add(Objects.requireNonNull(System.getProperty(JAVACD_ENV_VARIABLE)))
        .build();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    WorkerProcessPool<WorkerToolExecutor> workerToolPool =
        WorkerToolPoolFactory.getPool(
            context,
            launchJavaCDCommand,
            () -> {
              WorkerToolExecutor workerToolExecutor =
                  new JavaCDWorkerToolExecutor(context, launchJavaCDCommand);
              workerToolExecutor.launchWorker();
              return workerToolExecutor;
            },
            POOL_CAPACITY);

    try (BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool =
        workerToolPool.borrowWorkerProcess()) {
      WorkerToolExecutor workerToolExecutor = borrowedWorkerTool.get();
      return executeBuildJavaCommand(context, workerToolExecutor);
    }
  }

  private StepExecutionResult executeBuildJavaCommand(
      IsolatedExecutionContext context, WorkerToolExecutor workerToolExecutor)
      throws IOException, InterruptedException {
    try {
      ResultEvent resultEvent =
          workerToolExecutor.executeCommand(context.getActionId(), buildJavaCommand);

      return StepExecutionResult.builder()
          .setExitCode(resultEvent.getExitCode())
          .setExecutedCommand(launchJavaCDCommand)
          .setStderr(String.format("ResultEvent : %s", resultEvent))
          .build();

    } catch (ExecutionException e) {
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setExecutedCommand(launchJavaCDCommand)
          .setStderr(String.format("ActionId: %s", context.getActionId()))
          .setCause(e)
          .build();
    }
  }

  /** JavaCD worker tool. */
  private static class JavaCDWorkerToolExecutor extends BaseWorkerToolExecutor {

    private final ImmutableList<String> startWorkerToolCommand;

    public JavaCDWorkerToolExecutor(
        IsolatedExecutionContext context, ImmutableList<String> startWorkerToolCommand) {
      super(context);
      this.startWorkerToolCommand = startWorkerToolCommand;
    }

    @Override
    public ImmutableList<String> getStartWorkerToolCommand() {
      return startWorkerToolCommand;
    }
  }
}
