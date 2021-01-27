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
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

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
    DownwardApiProcessExecutor downwardApiProcessExecutor = context.getDownwardApiProcessExecutor();
    WorkerToolExecutor workerToolExecutor =
        new JavaCDWorkerToolExecutor(
            downwardApiProcessExecutor, buildJavaCommand, javaRuntimeLauncherCommand);

    String actionId = context.getActionId();
    workerToolExecutor.executeCommand(actionId);

    return StepExecutionResults.SUCCESS;
  }

  /** JavaCD worker tool. */
  private static class JavaCDWorkerToolExecutor extends WorkerToolExecutor {

    private final BuildJavaCommand buildJavaCommand;
    private final ImmutableList<String> javaRuntimeLauncherCommand;

    public JavaCDWorkerToolExecutor(
        DownwardApiProcessExecutor downwardApiProcessExecutor,
        BuildJavaCommand buildJavaCommand,
        ImmutableList<String> javaRuntimeLauncherCommand) {
      super(downwardApiProcessExecutor);
      this.buildJavaCommand = buildJavaCommand;
      this.javaRuntimeLauncherCommand = javaRuntimeLauncherCommand;
    }

    @Override
    protected void writeExecuteCommandTo(OutputStream outputStream) throws IOException {
      buildJavaCommand.writeDelimitedTo(outputStream);
    }

    @Override
    protected ImmutableList<String> getStartWorkerToolCommand() {
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
