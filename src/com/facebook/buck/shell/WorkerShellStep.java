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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class WorkerShellStep implements Step {

  private ProjectFilesystem filesystem;
  private Path tmpPath;
  private Path workingDir;
  private Optional<WorkerJobParams> cmdParams;
  private Optional<WorkerJobParams> bashParams;
  private Optional<WorkerJobParams> cmdExeParams;

  public WorkerShellStep(
      ProjectFilesystem filesystem,
      Path tmpPath,
      Path workingDir,
      Optional<WorkerJobParams> cmdParams,
      Optional<WorkerJobParams> bashParams,
      Optional<WorkerJobParams> cmdExeParams) {
    this.filesystem = filesystem;
    this.tmpPath = tmpPath;
    this.workingDir = workingDir;
    this.cmdParams = cmdParams;
    this.bashParams = bashParams;
    this.cmdExeParams = cmdExeParams;
  }

  @Override
  public int execute(final ExecutionContext context) throws InterruptedException {
    try {
      // Use the process's startup command as the key.
      String key = Joiner.on(' ').join(getCommand(context.getPlatform()));
      WorkerProcess process = getWorkerProcessForKey(key, context);
      process.ensureLaunchAndHandshake();
      WorkerJobResult result = process.submitAndWaitForJob(getExpandedJobArgs(context));
      Verbosity verbosity = context.getVerbosity();
      if (result.getStdout().isPresent() && !result.getStdout().get().isEmpty() &&
          verbosity.shouldPrintOutput()) {
        context.postEvent(ConsoleEvent.info("%s", result.getStdout().get()));
      }
      if (result.getStderr().isPresent() && !result.getStderr().get().isEmpty() &&
          verbosity.shouldPrintStandardInformation()) {
        context.postEvent(ConsoleEvent.warning("%s", result.getStderr().get()));
      }
      return result.getExitCode();
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error communicating with external process.");
    }
  }

  /**
   * Returns an existing WorkerProcess for the given key if one exists, else creates a new one.
   */
  private WorkerProcess getWorkerProcessForKey(
      String key,
      ExecutionContext context) throws IOException {
    ConcurrentMap<String, WorkerProcess> processMap = context.getWorkerProcesses();
    WorkerProcess process = processMap.get(key);
    if (process != null) {
      return process;
    }

    ProcessExecutorParams processParams = ProcessExecutorParams.builder()
        .setCommand(getCommand(context.getPlatform()))
        .setEnvironment(getEnvironmentForProcess(context))
        .setDirectory(workingDir.toFile())
        .build();
    WorkerProcess newProcess = new WorkerProcess(
        context.getProcessExecutor(),
        processParams,
        filesystem,
        tmpPath);

    WorkerProcess previousValue = processMap.putIfAbsent(key, newProcess);
    // If putIfAbsent does not return null, then that means another thread beat this thread
    // into putting an WorkerProcess in the map for this key. If that's the case, then we should
    // ignore newProcess and return the existing one.
    return previousValue == null ? newProcess : previousValue;
  }

  @VisibleForTesting
  ImmutableList<String> getCommand(Platform platform) {
    ImmutableList<String> executionArgs = platform == Platform.WINDOWS ?
        ImmutableList.of("cmd.exe", "/c") :
        ImmutableList.of("/bin/bash", "-e", "-c");

    WorkerJobParams paramsToUse = this.getWorkerJobParamsToUse(platform);
    return ImmutableList.<String>builder()
        .addAll(executionArgs)
        .add(FluentIterable.from(paramsToUse.getStartupCommand())
              .transform(Escaper.SHELL_ESCAPER)
              .append(paramsToUse.getStartupArgs())
              .join(Joiner.on(' ')))
        .build();
  }

  private String getExpandedJobArgs(ExecutionContext context) {
    return expandEnvironmentVariables(
        this.getWorkerJobParamsToUse(context.getPlatform()).getJobArgs(),
        getEnvironmentVariables(context));
  }

  @VisibleForTesting
  String expandEnvironmentVariables(
      String string,
      ImmutableMap<String, String> variablesToExpand) {
    for (Map.Entry<String, String> variable : variablesToExpand.entrySet()) {
      string = string
          .replace("$" + variable.getKey(), variable.getValue())
          .replace("${" + variable.getKey() + "}", variable.getValue());
    }
    return string;
  }

  @VisibleForTesting
  WorkerJobParams getWorkerJobParamsToUse(Platform platform) {
    if (platform == Platform.WINDOWS) {
      if (cmdExeParams.isPresent()) {
        return cmdExeParams.get();
      } else if (cmdParams.isPresent()) {
        return cmdParams.get();
      } else {
        throw new HumanReadableException("You must specify either \"cmd_exe\" or \"cmd\" for " +
            "this build rule.");
      }
    } else {
      if (bashParams.isPresent()) {
        return bashParams.get();
      } else if (cmdParams.isPresent()) {
        return cmdParams.get();
      } else {
        throw new HumanReadableException("You must specify either \"bash\" or \"cmd\" for " +
            "this build rule.");
      }
    }
  }

  /**
   * Returns the environment variables to use when starting the process and when expanding the
   * job arguments that get sent to the process.
   * <p>
   * By default, this method returns an empty map.
   * @param context that may be useful when determining environment variables to include.
   */
  protected ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.of();
  }

  @VisibleForTesting
  ImmutableMap<String, String> getEnvironmentForProcess(ExecutionContext context) {
    Map<String, String> envVars = this.getEnvironmentVariables(context);
    // Filter out duplicate keys
    Map<String, String> contextEnv = Maps.filterKeys(
        context.getEnvironment(),
        Predicates.not(Predicates.in(envVars.keySet())));
    return ImmutableMap.<String, String>builder()
        .putAll(envVars)
        .putAll(contextEnv)
        .build();
  }

  @Override
  public String getShortName() {
    return "worker";
  }

  @Override
  public final String getDescription(ExecutionContext context) {
    return String.format("Sending job with args \'%s\' to the process started with \'%s\'",
        getExpandedJobArgs(context),
        FluentIterable.from(getCommand(context.getPlatform()))
            .transform(Escaper.SHELL_ESCAPER)
            .join(Joiner.on(' ')));
  }
}
