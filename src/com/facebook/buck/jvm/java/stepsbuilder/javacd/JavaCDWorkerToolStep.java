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
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private static final String JAVACD_ENV_VARIABLE = "buck.javacd";

  private final BuildJavaCommand buildJavaCommand;
  private final ImmutableList<String> javaRuntimeLauncherCommand;

  public JavaCDWorkerToolStep(
      BuildJavaCommand buildJavaCommand, ImmutableList<String> javaRuntimeLauncherCommand) {
    super("javacd_wt");
    this.buildJavaCommand = buildJavaCommand;
    this.javaRuntimeLauncherCommand = javaRuntimeLauncherCommand;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    // TODO: msemko: get it from wt pool
    WorkerToolExecutor workerToolExecutor = getLaunchedWorkerTool(context);

    try {
      ResultEvent resultEvent =
          workerToolExecutor.executeCommand(context.getActionId(), buildJavaCommand);

      return StepExecutionResult.builder()
          .setExitCode(resultEvent.getExitCode())
          .setExecutedCommand(workerToolExecutor.getStartWorkerToolCommand())
          .setStderr(String.format("ResultEvent : %s", resultEvent))
          .build();

    } catch (ExecutionException e) {
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setExecutedCommand(workerToolExecutor.getStartWorkerToolCommand())
          .setStderr(String.format("ActionId: %s", context.getActionId()))
          .setCause(e)
          .build();
    } finally {
      // TODO: msemko: return wt into a pool
      workerToolExecutor.shutdown();
    }
  }

  private WorkerToolExecutor getLaunchedWorkerTool(IsolatedExecutionContext context)
      throws IOException {
    WorkerToolExecutor workerToolExecutor =
        new JavaCDWorkerToolExecutor(context, javaRuntimeLauncherCommand);
    workerToolExecutor.launchWorker();
    return workerToolExecutor;
  }

  /** JavaCD worker tool. */
  private static class JavaCDWorkerToolExecutor extends WorkerToolExecutor {

    private final ImmutableList<String> javaRuntimeLauncherCommand;

    public JavaCDWorkerToolExecutor(
        IsolatedExecutionContext context, ImmutableList<String> javaRuntimeLauncherCommand) {
      super(context);
      this.javaRuntimeLauncherCommand = javaRuntimeLauncherCommand;
    }

    @Override
    public ImmutableList<String> getStartWorkerToolCommand() {
      int runArgumentsCount = 2;
      return ImmutableList.<String>builderWithExpectedSize(
              javaRuntimeLauncherCommand.size() + runArgumentsCount)
          .addAll(javaRuntimeLauncherCommand)
          .add("-jar")
          .add(getJavaCDJarPath())
          .build();
    }

    private String getJavaCDJarPath() {
      return Objects.requireNonNull(System.getProperty(JAVACD_ENV_VARIABLE));
    }
  }
}
