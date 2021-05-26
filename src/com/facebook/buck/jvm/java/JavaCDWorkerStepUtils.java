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
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.WorkerToolLauncher;
import com.facebook.buck.workertool.impl.DefaultWorkerToolLauncher;
import com.facebook.buck.workertool.impl.WorkerToolPoolFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Collection of constants/methods used in JavaCD worker tool steps. */
public class JavaCDWorkerStepUtils {

  public static final String JAVACD_MAIN_CLASS =
      "com.facebook.buck.jvm.java.stepsbuilder.javacd.main.JavaCDWorkerToolMain";

  private JavaCDWorkerStepUtils() {}

  /** Creates {@link StepExecutionResult} from received from javacd {@link ResultEvent} */
  public static StepExecutionResult createStepExecutionResult(
      ImmutableList<String> executedCommand, ResultEvent resultEvent, String actionId) {
    int exitCode = resultEvent.getExitCode();
    StepExecutionResult.Builder builder =
        StepExecutionResult.builder().setExitCode(exitCode).setExecutedCommand(executedCommand);

    if (exitCode != 0) {
      builder.setStderr(
          String.format(
              "javacd action id: %s%n%s",
              actionId, resultEvent.getMessage().replace("\\n", System.lineSeparator())));
    }
    return builder.build();
  }

  /** Creates failed {@link StepExecutionResult} from the occurred {@link Exception} */
  public static StepExecutionResult createFailStepExecutionResult(
      ImmutableList<String> executedCommand, String actionId, Exception e) {
    return StepExecutionResult.builder()
        .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
        .setExecutedCommand(executedCommand)
        .setStderr(String.format("ActionId: %s", actionId))
        .setCause(e)
        .build();
  }

  /** Returns the startup command for launching javacd process. */
  public static ImmutableList<String> getLaunchJavaCDCommand(JavaCDParams javaCDParams) {
    ImmutableList<String> javaRuntimeLauncherCommand = javaCDParams.getJavaRuntimeLauncherCommand();
    ImmutableList<String> startCommandOptions = javaCDParams.getStartCommandOptions();

    return ImmutableList.<String>builderWithExpectedSize(
            javaRuntimeLauncherCommand.size() + startCommandOptions.size() + 4)
        .addAll(javaRuntimeLauncherCommand)
        .addAll(startCommandOptions)
        .add("-cp")
        .add(
            Objects.requireNonNull(
                BuckClasspath.getBuckBootstrapClasspathFromEnvVarOrNull(),
                BuckClasspath.BOOTSTRAP_ENV_VAR_NAME + " env variable is not set"))
        .add(BuckClasspath.BOOTSTRAP_MAIN_CLASS)
        .add(JAVACD_MAIN_CLASS)
        .build();
  }

  /** Returns {@link WorkerProcessPool.BorrowedWorkerProcess} from the passed pool. */
  public static WorkerProcessPool.BorrowedWorkerProcess<WorkerToolExecutor>
      borrowWorkerToolWithTimeout(
          WorkerProcessPool<WorkerToolExecutor> workerToolPool, int borrowFromPoolTimeoutInSeconds)
          throws InterruptedException {
    return workerToolPool
        .borrowWorkerProcess(borrowFromPoolTimeoutInSeconds, TimeUnit.SECONDS)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Cannot get a worker tool from a pool of the size: "
                        + workerToolPool.getCapacity()
                        + ". Time out of "
                        + borrowFromPoolTimeoutInSeconds
                        + " seconds passed."));
  }

  /** Returns {@link WorkerProcessPool} created for the passed {@code command} */
  public static WorkerProcessPool<WorkerToolExecutor> getWorkerToolPool(
      IsolatedExecutionContext context,
      ImmutableList<String> startupCommand,
      JavaCDParams javaCDParams) {
    return WorkerToolPoolFactory.getPool(
        context,
        startupCommand,
        () -> {
          WorkerToolLauncher workerToolLauncher = new DefaultWorkerToolLauncher(context);
          try (Scope ignored = PerfEvents.scope(context.getIsolatedEventBus(), "launch_worker")) {
            return workerToolLauncher.launchWorker(
                startupCommand,
                ImmutableMap.of(
                    BuckClasspath.ENV_VAR_NAME,
                    Objects.requireNonNull(
                        BuckClasspath.getBuckClasspathFromEnvVarOrNull(),
                        BuckClasspath.ENV_VAR_NAME + " env variable is not set")));
          }
        },
        javaCDParams.getWorkerToolPoolSize());
  }
}
