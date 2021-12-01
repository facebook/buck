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

package com.facebook.buck.util;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.util.memory.LinuxTimeParser;
import com.facebook.buck.util.memory.ResourceUsage;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A {@link ProcessExecutor} that delegates process execution to a child executor, except that the
 * child executor is instructed to keep track of the resource utilization of the process being
 * executed.
 */
public class ResourceMonitoringProcessExecutor extends DelegateProcessExecutor {

  private static final Logger LOG = Logger.get(ResourceMonitoringProcessExecutor.class);

  public ResourceMonitoringProcessExecutor(ProcessExecutor delegateExecutor) {
    super(delegateExecutor);
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {
    // TODO(swgillespie) - this approach is Linux only, we'll need additional abstraction to work on
    // other platforms.
    //
    // GNU time (/usr/bin/time) takes two useful parameters that we use here:
    //   1) -f, which allows us to pass a format string
    // (see https://man7.org/linux/man-pages/man1/time.1.html) that GNU time interprets when
    // producing a string representing the resource utilization of the process we're spawning.
    //   2) -o, which instructs GNU time to write the string generated by interpreting the format
    // string to a file instead of echoing it to standard error.
    //
    // For this purpose, we'll create a temporary file and pass that to time, so we can read it when
    // the process completes.
    NamedTemporaryFile timeOutputFile = new NamedTemporaryFile("time", ".report");
    ImmutableList<String> timeCommand =
        ImmutableList.<String>builderWithExpectedSize(params.getCommand().size() + 5)
            .add("/usr/bin/time")
            .add("-f")
            .add("rss %M")
            .add("-o")
            .add(timeOutputFile.get().toString())
            .addAll(params.getCommand())
            .build();
    ProcessExecutorParams childParams =
        ProcessExecutorParams.builder().from(params).setCommand(timeCommand).build();
    LaunchedProcess delegate = getDelegate().launchProcess(childParams, context);
    return new ResourceMonitoringLaunchedProcess(delegate, timeOutputFile, params.getCommand());
  }

  @Override
  public Result waitForLaunchedProcess(LaunchedProcess launchedProcess)
      throws InterruptedException {
    Result delegateResult = getDelegate().waitForLaunchedProcess(launchedProcess);
    return wrapExecutionResultWithResourceUsage(launchedProcess, delegateResult);
  }

  @Override
  public Result waitForLaunchedProcessWithTimeout(
      LaunchedProcess launchedProcess, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Result delegateResult =
        getDelegate().waitForLaunchedProcessWithTimeout(launchedProcess, millis, timeOutHandler);
    return wrapExecutionResultWithResourceUsage(launchedProcess, delegateResult);
  }

  @Override
  public Result execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Result delegateResult =
        getDelegate().execute(launchedProcess, options, stdin, timeOutMs, timeOutHandler);
    return wrapExecutionResultWithResourceUsage(launchedProcess, delegateResult);
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream stdOutStream, PrintStream stdErrStream) {
    return new ResourceMonitoringProcessExecutor(
        getDelegate().cloneWithOutputStreams(stdOutStream, stdErrStream));
  }

  @Override
  public ProcessExecutor withDownwardAPI(
      DownwardApiProcessExecutorFactory factory,
      NamedPipeEventHandlerFactory namedPipeEventHandlerFactory,
      IsolatedEventBus buckEventBus,
      ActionId actionId,
      Clock clock) {
    return new ResourceMonitoringProcessExecutor(
        getDelegate()
            .withDownwardAPI(factory, namedPipeEventHandlerFactory, buckEventBus, actionId, clock));
  }

  private Optional<ResourceUsage> extractResourceUsage(LaunchedProcess process) {
    if (!(process instanceof ResourceMonitoringLaunchedProcess)) {
      return Optional.empty();
    }

    // Note that this does "move" out of rmlProcess.timeFile. This is normal and expected;
    // unfortunately LaunchedProcesses can be closed anywhere from zero to two times depending on
    // code paths, so it's safest to dispose of it here.
    var rmlProcess = (ResourceMonitoringLaunchedProcess) process;
    try (var reportFile = rmlProcess.timeFile) {
      var reportPath = reportFile.get();
      try {
        var contents = Files.readString(reportPath);
        var usage = new LinuxTimeParser().parse(contents);
        return Optional.of(usage);
      } catch (IOException e) {
        LOG.warn(e, "Failed to read resource usage from time report file: %s", reportPath);
        return Optional.empty();
      }
    }
  }

  private Result wrapExecutionResultWithResourceUsage(
      LaunchedProcess launchedProcess, Result delegateResult) {
    return new Result(
        delegateResult.getExitCode(),
        delegateResult.isTimedOut(),
        delegateResult.getStdout(),
        delegateResult.getStderr(),
        delegateResult.getCommand(),
        extractResourceUsage(launchedProcess));
  }

  /**
   * A {@link DelegateLaunchedProcess} that keeps track of the named temporary file that time is
   * going to write to when the process is complete.
   *
   * <p>It pretends to be the "original command": `getCommand` returns the original command given to
   * `launchProcess` and not the modified one that is ultimately executed (the one including
   * /usr/bin/time).
   */
  public static class ResourceMonitoringLaunchedProcess extends DelegateLaunchedProcess {

    private final NamedTemporaryFile timeFile;
    private final ImmutableList<String> originalCommand;

    public ResourceMonitoringLaunchedProcess(
        LaunchedProcess delegate,
        NamedTemporaryFile timeFile,
        ImmutableList<String> originalCommand) {
      super(delegate);
      this.timeFile = timeFile;
      this.originalCommand = originalCommand;
    }

    @Override
    public ImmutableList<String> getCommand() {
      return originalCommand;
    }
  }
}
