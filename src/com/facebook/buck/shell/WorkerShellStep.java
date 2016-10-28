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
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkerShellStep implements Step {

  private ProjectFilesystem filesystem;
  private Optional<WorkerJobParams> cmdParams;
  private Optional<WorkerJobParams> bashParams;
  private Optional<WorkerJobParams> cmdExeParams;

  public WorkerShellStep(
      ProjectFilesystem filesystem,
      Optional<WorkerJobParams> cmdParams,
      Optional<WorkerJobParams> bashParams,
      Optional<WorkerJobParams> cmdExeParams) {
    this.filesystem = filesystem;
    this.cmdParams = cmdParams;
    this.bashParams = bashParams;
    this.cmdExeParams = cmdExeParams;
  }

  @Override
  public StepExecutionResult execute(final ExecutionContext context) throws InterruptedException {
    WorkerProcessPool pool = null;
    WorkerProcess process = null;
    try {
      // Use the process's startup command as the key.
      pool = getWorkerProcessPool(context);
      process = pool.borrowWorkerProcess(); // blocks until a WorkerProcess becomes available
      WorkerJobResult result = process.submitAndWaitForJob(getExpandedJobArgs(context));
      Verbosity verbosity = context.getVerbosity();
      if (result.getStdout().isPresent() && !result.getStdout().get().isEmpty() &&
          verbosity.shouldPrintOutput()) {
        context.postEvent(ConsoleEvent.info("%s", result.getStdout().get()));
      }
      if (result.getStderr().isPresent() && !result.getStderr().get().isEmpty() &&
          verbosity.shouldPrintStandardInformation()) {
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
        pool.returnWorkerProcess(process);
      }
    }
  }

  /**
   * Returns an existing WorkerProcessPool for the given key if one exists, else creates a new one.
   */
  private WorkerProcessPool getWorkerProcessPool(final ExecutionContext context) {
    WorkerJobParams paramsToUse = getWorkerJobParamsToUse(context.getPlatform());

    ConcurrentMap<String, WorkerProcessPool> processPoolMap;
    final String key;
    final HashCode workerHash;
    if (paramsToUse.getPersistentWorkerKey().isPresent() &&
        context.getPersistentWorkerPools().isPresent()) {
      processPoolMap = context.getPersistentWorkerPools().get();
      workerHash = paramsToUse.getWorkerHash().get();
      key = paramsToUse.getPersistentWorkerKey().get();
    } else {
      processPoolMap = context.getWorkerProcessPools();
      key = Joiner.on(' ').join(getCommand(context.getPlatform()));
      workerHash = Hashing.sha1().hashString(key, Charsets.UTF_8);
    }

    // If the worker pool has a different hash, recreate the pool.
    WorkerProcessPool pool = processPoolMap.get(key);
    if (pool != null && !pool.getPoolHash().equals(workerHash)) {
      if (processPoolMap.remove(key, pool)) {
        pool.close();
      }
      pool = processPoolMap.get(key);
    }

    if (pool == null) {
      final ProcessExecutorParams processParams = ProcessExecutorParams.builder()
          .setCommand(getCommand(context.getPlatform()))
          .setEnvironment(getEnvironmentForProcess(context))
          .setDirectory(filesystem.getRootPath())
          .build();

      final Path workerTmpDir = paramsToUse.getTempDir();
      final AtomicInteger workerNumber = new AtomicInteger(0);

      WorkerProcessPool newPool = new WorkerProcessPool(
          paramsToUse.getMaxWorkers(), workerHash) {
        @Override
        protected WorkerProcess startWorkerProcess() throws IOException {
          Path tmpDir = workerTmpDir.resolve(Integer.toString(workerNumber.getAndIncrement()));
          filesystem.mkdirs(tmpDir);

          WorkerProcess process = createWorkerProcess(processParams, context, tmpDir);
          process.ensureLaunchAndHandshake();
          return process;
        }
      };
      WorkerProcessPool previousPool = processPoolMap.putIfAbsent(key, newPool);
      // If putIfAbsent does not return null, then that means another thread beat this thread
      // into putting an WorkerProcessPool in the map for this key. If that's the case, then we
      // should ignore newPool and return the existing one.
      pool = previousPool == null ? newPool : previousPool;
    }

    int poolCapacity = pool.getCapacity();
    if (poolCapacity != paramsToUse.getMaxWorkers()) {
      context.postEvent(ConsoleEvent.warning(
          "There are two 'worker_tool' targets declared with the same command (%s), but " +
              "different 'max_worker' settings (%d and %d). Only the first capacity is applied. " +
              "Consolidate these workers to avoid this warning.",
          key,
          poolCapacity,
          paramsToUse.getMaxWorkers()));
    }

    return pool;
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

  @VisibleForTesting
  String getExpandedJobArgs(ExecutionContext context) {
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
   * Returns the environment variables to use when expanding the job arguments that get
   * sent to the process.
   * <p>
   * By default, this method returns an empty map.
   * @param context that may be useful when determining environment variables to include.
   */
  protected ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.of();
  }

  @VisibleForTesting
  ImmutableMap<String, String> getEnvironmentForProcess(ExecutionContext context) {
    Path tmpDir = getWorkerJobParamsToUse(context.getPlatform()).getTempDir();

    Map<String, String> envVars = Maps.newHashMap(context.getEnvironment());
    envVars.put("TMP", filesystem.resolve(tmpDir).toString());
    envVars.putAll(getWorkerJobParamsToUse(context.getPlatform()).getStartupEnvironment());
    return ImmutableMap.copyOf(envVars);
  }

  @VisibleForTesting
  WorkerProcess createWorkerProcess(
      ProcessExecutorParams processParams,
      ExecutionContext context,
      Path tmpDir) throws IOException {
    return new WorkerProcess(
        context.getProcessExecutor(),
        processParams,
        filesystem,
        tmpDir);
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
