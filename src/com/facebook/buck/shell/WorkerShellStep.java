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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

public class WorkerShellStep implements Step {

  private Optional<WorkerJobParams> cmdParams;
  private Optional<WorkerJobParams> bashParams;
  private Optional<WorkerJobParams> cmdExeParams;
  private WorkerProcessPoolFactory factory;

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
      Optional<WorkerJobParams> cmdParams,
      Optional<WorkerJobParams> bashParams,
      Optional<WorkerJobParams> cmdExeParams,
      WorkerProcessPoolFactory factory) {
    this.cmdParams = cmdParams;
    this.bashParams = bashParams;
    this.cmdExeParams = cmdExeParams;
    this.factory = factory;
  }

  @Override
  public StepExecutionResult execute(final ExecutionContext context) throws InterruptedException {
    WorkerProcessPool pool = null;
    WorkerProcess process = null;
    try {
      // Use the process's startup command as the key.
      WorkerJobParams paramsToUse = getWorkerJobParamsToUse(context.getPlatform());
      pool = factory.getWorkerProcessPool(context, paramsToUse.getWorkerProcessParams());
      process = pool.borrowWorkerProcess();
      WorkerJobResult result = process.submitAndWaitForJob(getExpandedJobArgs(context));
      pool.returnWorkerProcess(process);
      process = null; // to avoid finally below

      Verbosity verbosity = context.getVerbosity();
      if (result.getStdout().isPresent()
          && !result.getStdout().get().isEmpty()
          && verbosity.shouldPrintOutput()) {
        context.postEvent(ConsoleEvent.info("%s", result.getStdout().get()));
      }
      if (result.getStderr().isPresent()
          && !result.getStderr().get().isEmpty()
          && verbosity.shouldPrintStandardInformation()) {
        if (result.getExitCode() == 0) {
          context.postEvent(ConsoleEvent.warning("%s", result.getStderr().get()));
        } else {
          context.postEvent(ConsoleEvent.severe("%s", result.getStderr().get()));
        }
      }
      return StepExecutionResult.of(result.getExitCode());
    } catch (Exception e) {
      throw new HumanReadableException(e, "Error communicating with external process.");
    } finally {
      if (pool != null && process != null) {
        pool.destroyWorkerProcess(process);
      }
    }
  }

  @VisibleForTesting
  String getExpandedJobArgs(ExecutionContext context) {
    return expandEnvironmentVariables(
        this.getWorkerJobParamsToUse(context.getPlatform()).getJobArgs(),
        getEnvironmentVariables(context));
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
   *
   * @param context that may be useful when determining environment variables to include.
   */
  protected ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
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
        FluentIterable.from(
                factory.getCommand(
                    context.getPlatform(),
                    getWorkerJobParamsToUse(context.getPlatform()).getWorkerProcessParams()))
            .transform(Escaper.SHELL_ESCAPER)
            .join(Joiner.on(' ')));
  }
}
