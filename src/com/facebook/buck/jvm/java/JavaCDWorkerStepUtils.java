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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.event.PerfEvents;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.java.JavaRuntimeUtils;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.WorkerToolLauncher;
import com.facebook.buck.workertool.impl.DefaultWorkerToolLauncher;
import com.facebook.buck.workertool.impl.WorkerToolPoolFactory;
import com.google.common.annotations.VisibleForTesting;
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
      ImmutableList<String> executedCommand, ResultEvent resultEvent, ActionId actionId) {
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
      ImmutableList<String> executedCommand, ActionId actionId, Exception e) {
    return StepExecutionResult.builder()
        .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
        .setExecutedCommand(executedCommand)
        .setStderr(String.format("ActionId: %s", actionId))
        .setCause(e)
        .build();
  }

  /** Returns the startup command for launching javacd process. */
  public static ImmutableList<String> getLaunchJavaCDCommand(
      JavaCDParams javaCDParams, AbsPath ruleCellRoot) {
    ImmutableList<String> startCommandOptions = javaCDParams.getStartCommandOptions();
    ImmutableList<String> commonJvmParams =
        getCommonJvmParams(ruleCellRoot.resolve(javaCDParams.getLogDirectory()));

    String classpath =
        Objects.requireNonNull(
            BuckClasspath.getBuckBootstrapClasspathFromEnvVarOrNull(),
            BuckClasspath.BOOTSTRAP_ENV_VAR_NAME + " env variable is not set");
    ImmutableList<String> command =
        ImmutableList.of("-cp", classpath, BuckClasspath.BOOTSTRAP_MAIN_CLASS, JAVACD_MAIN_CLASS);

    return ImmutableList.<String>builderWithExpectedSize(
            1 + commonJvmParams.size() + startCommandOptions.size() + command.size())
        .add(JavaRuntimeUtils.getBucksJavaBinCommand())
        .addAll(commonJvmParams)
        .addAll(startCommandOptions)
        .addAll(command)
        .build();
  }

  /** Returns common jvm params for javacd */
  @VisibleForTesting
  public static ImmutableList<String> getCommonJvmParams(AbsPath logDirectory) {
    return ImmutableList.of(
        "-Dfile.encoding=" + UTF_8.name(),
        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=" + logDirectory.toString());
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
          try (Scope ignored =
              PerfEvents.scope(
                  context.getIsolatedEventBus(), context.getActionId(), "launch_worker")) {
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
