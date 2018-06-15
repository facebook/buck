/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.worker.WorkerJobParams;
import com.facebook.buck.worker.WorkerJobResult;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class WorkerShellStep implements Step {

  private Optional<WorkerJobParams> cmdParams;
  private Optional<WorkerJobParams> bashParams;
  private Optional<WorkerJobParams> cmdExeParams;
  private WorkerProcessPoolFactory factory;

  /** Target using this worker shell step. */
  BuildTarget buildTarget;

  /**
   * Creates new shell step that uses worker process to delegate work. If platform-specific params
   * are present they are used in favor of universal params.
   *
   * @param cmdParams Universal, platform independent params, something that would work for both
   *     Linux/macOS and Windows platforms.
   * @param bashParams Used in Linux/macOS environment, specifies the arguments that are passed into
   *     bash shell.
   * @param cmdExeParams Used in Windows environment, specifies the arguments that are passed into
   *     cmd.exe (Windows shell).
   */
  public WorkerShellStep(
      BuildTarget buildTarget,
      Optional<WorkerJobParams> cmdParams,
      Optional<WorkerJobParams> bashParams,
      Optional<WorkerJobParams> cmdExeParams,
      WorkerProcessPoolFactory factory) {
    this.buildTarget = buildTarget;
    this.cmdParams = cmdParams;
    this.bashParams = bashParams;
    this.cmdExeParams = cmdExeParams;
    this.factory = factory;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    // Use the process's startup command as the key.
    WorkerJobParams paramsToUse = getWorkerJobParamsToUse(context.getPlatform());
    WorkerProcessPool pool =
        factory.getWorkerProcessPool(context, paramsToUse.getWorkerProcessParams());
    WorkerJobResult result;
    try (BorrowedWorkerProcess process = pool.borrowWorkerProcess()) {
      result = process.submitAndWaitForJob(getExpandedJobArgs(context));
    }

    Verbosity verbosity = context.getVerbosity();
    boolean showStdout =
        result.getStdout().isPresent()
            && !result.getStdout().get().isEmpty()
            && verbosity.shouldPrintOutput();
    boolean showStderr =
        result.getStderr().isPresent()
            && !result.getStderr().get().isEmpty()
            && verbosity.shouldPrintStandardInformation();
    if (showStdout) {
      context.postEvent(ConsoleEvent.info("%s", result.getStdout().get()));
    }
    if (showStderr) {
      if (result.getExitCode() == 0) {
        context.postEvent(ConsoleEvent.warning("%s", result.getStderr().get()));
      } else {
        context.postEvent(ConsoleEvent.severe("%s", result.getStderr().get()));
      }
    }
    if (showStdout || showStderr) {
      context.postEvent(ConsoleEvent.info("    When building rule %s:", buildTarget));
    }
    return StepExecutionResult.of(result.getExitCode());
  }

  @VisibleForTesting
  String getExpandedJobArgs(ExecutionContext context) {
    return expandEnvironmentVariables(
        this.getWorkerJobParamsToUse(context.getPlatform()).getJobArgs(),
        getEnvironmentVariables());
  }

  @VisibleForTesting
  String expandEnvironmentVariables(String string, ImmutableMap<String, String> variablesToExpand) {
    for (Map.Entry<String, String> variable : variablesToExpand.entrySet()) {
      string =
          string
              .replace("$" + variable.getKey(), variable.getValue())
              .replace("${" + variable.getKey() + "}", variable.getValue());
    }
    return string;
  }

  @VisibleForTesting
  public WorkerJobParams getWorkerJobParamsToUse(Platform platform) {
    if (platform == Platform.WINDOWS) {
      if (cmdExeParams.isPresent()) {
        return cmdExeParams.get();
      } else if (cmdParams.isPresent()) {
        return cmdParams.get();
      } else {
        throw new HumanReadableException(
            "You must specify either \"cmd_exe\" or \"cmd\" for " + "this build rule.");
      }
    } else {
      if (bashParams.isPresent()) {
        return bashParams.get();
      } else if (cmdParams.isPresent()) {
        return cmdParams.get();
      } else {
        throw new HumanReadableException(
            "You must specify either \"bash\" or \"cmd\" for " + "this build rule.");
      }
    }
  }

  /**
   * Returns the environment variables to use when expanding the job arguments that get sent to the
   * process.
   *
   * <p>By default, this method returns an empty map.
   */
  protected ImmutableMap<String, String> getEnvironmentVariables() {
    return ImmutableMap.of();
  }

  @Override
  public String getShortName() {
    return "worker";
  }

  @VisibleForTesting
  WorkerProcessPoolFactory getFactory() {
    return factory;
  }

  @Override
  public final String getDescription(ExecutionContext context) {
    return String.format(
        "Sending job with args \'%s\' to the process started with \'%s\'",
        getExpandedJobArgs(context),
        factory
            .getCommand(
                context.getPlatform(),
                getWorkerJobParamsToUse(context.getPlatform()).getWorkerProcessParams())
            .stream()
            .map(Escaper.SHELL_ESCAPER)
            .collect(Collectors.joining(" ")));
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }
}
