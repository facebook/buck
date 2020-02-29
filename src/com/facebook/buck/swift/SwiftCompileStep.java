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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** A step that compiles Swift sources to a single module. */
class SwiftCompileStep implements Step {

  private static final Logger LOG = Logger.get(SwiftCompileStep.class);

  private final AbsPath compilerCwd;
  private final ImmutableMap<String, String> compilerEnvironment;
  private final ImmutableList<String> compilerCommand;

  SwiftCompileStep(
      AbsPath compilerCwd,
      Map<String, String> compilerEnvironment,
      Iterable<String> compilerCommand) {
    this.compilerCwd = compilerCwd;
    this.compilerEnvironment = ImmutableMap.copyOf(compilerEnvironment);
    this.compilerCommand = ImmutableList.copyOf(compilerCommand);
  }

  @Override
  public String getShortName() {
    return "swift compile";
  }

  private ProcessExecutorParams makeProcessExecutorParams(ExecutionContext context) {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(compilerCwd.getPath());
    builder.setEnvironment(compilerEnvironment);
    builder.setCommand(
        ImmutableList.<String>builder()
            .addAll(compilerCommand)
            .addAll(getColorArguments(context.getAnsi().isAnsiTerminal()))
            .build());
    return builder.build();
  }

  private Iterable<String> getColorArguments(boolean allowColorInDiagnostics) {
    return allowColorInDiagnostics ? ImmutableList.of("-color-diagnostics") : ImmutableList.of();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutorParams params = makeProcessExecutorParams(context);

    // TODO(markwang): parse the output, print build failure errors, etc.
    LOG.debug("%s", compilerCommand);

    Result processResult = context.getProcessExecutor().launchAndExecute(params);

    int result = processResult.getExitCode();
    Optional<String> stderr = processResult.getStderr();
    if (result != StepExecutionResults.SUCCESS_EXIT_CODE) {
      LOG.error("Error running %s: %s", getDescription(context), stderr);
    }
    return StepExecutionResult.of(processResult);
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(compilerCommand);
  }
}
