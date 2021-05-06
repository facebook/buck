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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.facebook.buck.workertool.WorkerToolLauncher;
import com.facebook.buck.workertool.impl.DefaultWorkerToolLauncher;
import com.facebook.buck.workertool.impl.WorkerToolPoolFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** JavaCD worker tool isolated step. */
public class JavaCDWorkerToolStep extends AbstractIsolatedExecutionStep {

  private final BuildJavaCommand buildJavaCommand;
  private final JavaCDParams javaCDParams;

  public JavaCDWorkerToolStep(BuildJavaCommand buildJavaCommand, JavaCDParams javaCDParams) {
    super("javacd_wt");
    this.buildJavaCommand = buildJavaCommand;
    this.javaCDParams = javaCDParams;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    AbsPath ruleCellRoot = context.getRuleCellRoot();
    ImmutableList<String> launchJavaCDCommand = getLaunchJavaCDCommand(javaCDParams);
    AbsPath jarPath = getJavaCDJarPath(ruleCellRoot, javaCDParams);

    WorkerProcessPool<WorkerToolExecutor> workerToolPool =
        WorkerToolPoolFactory.getPool(
            context,
            launchJavaCDCommand,
            () -> {
              WorkerToolLauncher workerToolLauncher = new DefaultWorkerToolLauncher(context);
              return workerToolLauncher.launchWorker(
                  launchJavaCDCommand,
                  ImmutableMap.of(BuckClasspath.ENV_VAR_NAME, jarPath.toString()));
            },
            javaCDParams.getWorkerToolPoolSize());

    try (BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool =
        borrowWorkerToolWithTimeout(workerToolPool)) {
      WorkerToolExecutor workerToolExecutor = borrowedWorkerTool.get();
      return executeBuildJavaCommand(context, workerToolExecutor, launchJavaCDCommand);
    }
  }

  private ImmutableList<String> getLaunchJavaCDCommand(JavaCDParams javaCDParams) {
    ImmutableList<String> javaRuntimeLauncherCommand = javaCDParams.getJavaRuntimeLauncherCommand();
    ImmutableList<String> startCommandOptions = javaCDParams.getStartCommandOptions();

    return ImmutableList.<String>builderWithExpectedSize(
            javaRuntimeLauncherCommand.size() + startCommandOptions.size() + 4)
        .addAll(javaRuntimeLauncherCommand)
        .addAll(startCommandOptions)
        .add("-cp")
        .add(BuckClasspath.getBuckBootstrapClasspathFromEnvVarOrNull())
        .add(JavaLibraryRules.BOOTSTRAP_MAIN_CLASS)
        .add(JavaLibraryRules.JAVACD_MAIN_CLASS)
        .build();
  }

  private AbsPath getJavaCDJarPath(AbsPath ruleCellRoot, JavaCDParams javaCDParams)
      throws IOException {
    Supplier<RelPath> javacdBinaryPathSupplier = javaCDParams.getJavacdBinaryPathSupplier();
    AbsPath jarPath = ruleCellRoot.resolve(javacdBinaryPathSupplier.get());
    if (!Files.exists(jarPath.getPath())) {
      throw new IOException("jar " + jarPath + " is not exist on env");
    }
    return jarPath;
  }

  private BorrowedWorkerProcess<WorkerToolExecutor> borrowWorkerToolWithTimeout(
      WorkerProcessPool<WorkerToolExecutor> workerToolPool) throws InterruptedException {
    return workerToolPool
        .borrowWorkerProcess(javaCDParams.getBorrowFromPoolTimeoutInSeconds(), TimeUnit.SECONDS)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Cannot get a worker tool from a pool of the size: "
                        + workerToolPool.getCapacity()
                        + ". Time out of "
                        + javaCDParams.getBorrowFromPoolTimeoutInSeconds()
                        + " seconds passed."));
  }

  private StepExecutionResult executeBuildJavaCommand(
      IsolatedExecutionContext context,
      WorkerToolExecutor workerToolExecutor,
      ImmutableList<String> launchJavaCDCommand)
      throws IOException, InterruptedException {
    try {
      ResultEvent resultEvent =
          workerToolExecutor.executeCommand(context.getActionId(), buildJavaCommand);
      int exitCode = resultEvent.getExitCode();

      StepExecutionResult.Builder builder =
          StepExecutionResult.builder()
              .setExitCode(exitCode)
              .setExecutedCommand(launchJavaCDCommand);

      if (exitCode != 0) {
        builder.setStderr(
            String.format(
                "javacd action id: %s%n%s",
                context.getActionId(),
                resultEvent.getMessage().replace("\\n", System.lineSeparator())));
      }

      return builder.build();

    } catch (ExecutionException e) {
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setExecutedCommand(launchJavaCDCommand)
          .setStderr(String.format("ActionId: %s", context.getActionId()))
          .setCause(e)
          .build();
    }
  }
}
