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

package com.facebook.buck.step.isolatedsteps.shell;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Delegate that handles common functionality between {@link com.facebook.buck.shell.ShellStep} and
 * {@link IsolatedShellStep}.
 */
public class ShellStepDelegate {

  private static final OperatingSystemMXBean OS_JMX = ManagementFactory.getOperatingSystemMXBean();

  /** Absolute path to the working directory where this shell step will execute its command. */
  private final Path workingDirectory;

  private final boolean withDownwardApi;
  private final Logger logger;

  /**
   * This is set if {@link #shouldPrintStdout(Verbosity)} returns {@code true} when the command is
   * executed.
   */
  private Optional<String> stdout;

  /**
   * This is set if {@link #shouldPrintStderr(Verbosity)} returns {@code true} when the command is
   * executed.
   */
  private Optional<String> stderr;

  private long startTime = 0L;
  private long endTime = 0L;

  public ShellStepDelegate(Path workingDirectory, boolean withDownwardApi, Logger logger) {
    this.workingDirectory = Objects.requireNonNull(workingDirectory);
    this.withDownwardApi = withDownwardApi;
    this.stdout = Optional.empty();
    this.stderr = Optional.empty();
    this.logger = logger;

    if (!workingDirectory.isAbsolute()) {
      logger.info("Working directory is not absolute: %s", workingDirectory);
    }
  }

  /** Executes the given shell command. */
  public StepExecutionResult executeCommand(
      IsolatedExecutionContext context,
      RelPath buildCellRootPath,
      ImmutableList<String> command,
      ImmutableMap<String, String> environmentVariables,
      Optional<String> stdin,
      Optional<Long> timeout,
      Optional<Consumer<Process>> timeoutHandler,
      boolean shouldPrintStdOut,
      boolean shouldPrintStdErr,
      Consumer<ImmutableSet.Builder<ProcessExecutor.Option>> addOptions,
      Function<ProcessExecutor.Result, Integer> getExitCodeFromResult)
      throws IOException, InterruptedException {
    if (command.isEmpty()) {
      return StepExecutionResults.SUCCESS;
    }
    // Kick off a Process in which this IsolatedShellCommand will be run.
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setCommand(command);
    Map<String, String> environment = new HashMap<>();
    setProcessEnvironment(context, environment, workingDirectory.toString(), environmentVariables);

    if (logger.isDebugEnabled()) {
      logger.debug("Environment: %s", Joiner.on(" ").withKeyValueSeparator('=').join(environment));
    }
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    builder.setDirectory(
        ProjectFilesystemUtils.getPathForRelativePath(
                context.getRuleCellRoot(), buildCellRootPath.getPath())
            .resolve(workingDirectory));

    if (stdin.isPresent()) {
      builder.setRedirectInput(ProcessBuilder.Redirect.PIPE);
    }

    double initialLoad = OS_JMX.getSystemLoadAverage();
    startTime = System.currentTimeMillis();
    ProcessExecutor.Result result =
        launchAndInteractWithProcess(
            context,
            builder.build(),
            stdin,
            timeout,
            timeoutHandler,
            shouldPrintStdOut,
            shouldPrintStdErr,
            addOptions);
    int exitCode = getExitCodeFromResult.apply(result);
    endTime = System.currentTimeMillis();
    double endLoad = OS_JMX.getSystemLoadAverage();

    if (logger.isDebugEnabled()) {
      boolean hasOutput =
          (stdout.isPresent() && !stdout.get().isEmpty())
              || (stderr.isPresent() && !stderr.get().isEmpty());
      String outputFormat = hasOutput ? "\nstdout:\n%s\nstderr:\n%s\n" : " (no output)%s%s";
      logger.debug(
          "%s: exit code: %d. os load (before, after): (%f, %f). CPU count: %d." + outputFormat,
          command,
          exitCode,
          initialLoad,
          endLoad,
          OS_JMX.getAvailableProcessors(),
          stdout.orElse(""),
          stderr.orElse(""));
    }

    return StepExecutionResult.builder()
        .setExitCode(exitCode)
        .setExecutedCommand(result.getCommand())
        .setStderr(stderr)
        .build();
  }

  /**
   * Sets the environment variables from the given context, the given working directory, and any
   * additional environment variables onto the given environment. Not side-effect free.
   */
  public void setProcessEnvironment(
      IsolatedExecutionContext context,
      Map<String, String> environment,
      String workDir,
      ImmutableMap<String, String> additionalEnvVars) {

    // Replace environment with client environment.
    environment.clear();
    environment.putAll(context.getEnvironment());

    // Make sure the special PWD variable matches the working directory
    // of the process (unless otherwise set).
    environment.put("PWD", workDir);

    // Add extra environment variables for step, if appropriate.
    if (!additionalEnvVars.isEmpty()) {
      environment.putAll(additionalEnvVars);
    }
  }

  /** @return the exit code interpreted from the {@code result}. */
  public int getExitCodeFromResult(ProcessExecutor.Result result) {
    return result.getExitCode();
  }

  /**
   * Launches and executes a process in the given context with the given process executor params.
   * Posts events if applicable.
   */
  public ProcessExecutor.Result launchAndInteractWithProcess(
      IsolatedExecutionContext context,
      ProcessExecutorParams params,
      Optional<String> stdin,
      Optional<Long> timeout,
      Optional<Consumer<Process>> timeoutHandler,
      boolean shouldPrintStdOut,
      boolean shouldPrintStdErr,
      Consumer<ImmutableSet.Builder<ProcessExecutor.Option>> addOptions)
      throws InterruptedException, IOException {
    ImmutableSet.Builder<ProcessExecutor.Option> options = ImmutableSet.builder();

    addOptions.accept(options);

    ProcessExecutor executor = context.getProcessExecutor();
    if (withDownwardApi) {
      executor =
          executor.withDownwardAPI(
              DownwardApiProcessExecutor.FACTORY, context.getIsolatedEventBus());
    }

    ProcessExecutor.Result result =
        executor.launchAndExecute(params, options.build(), stdin, timeout, timeoutHandler);
    stdout = result.getStdout();
    stderr = result.getStderr();

    if (stdout.isPresent()
        && !stdout.get().isEmpty()
        && (result.getExitCode() != 0 || shouldPrintStdOut)) {
      context.postEvent(ConsoleEvent.info("%s", stdout.get()));
    }
    if (stderr.isPresent()
        && !stderr.get().isEmpty()
        && (result.getExitCode() != 0 || shouldPrintStdErr)) {
      context.postEvent(ConsoleEvent.warning("%s", stderr.get()));
    }

    return result;
  }

  /** Adds relevant {@link ProcessExecutor.Option} to the given options. Not side-effect free. */
  public void addOptions(ImmutableSet.Builder<ProcessExecutor.Option> options) {
    options.add(ProcessExecutor.Option.IS_SILENT);
  }

  /**
   * Returns the time taken for launching and executing the process that runs the shell command args
   * associated with this {@code IsolatedShellStep}. Not pure.
   */
  public long getDuration() {
    Preconditions.checkState(startTime > 0);
    Preconditions.checkState(endTime > 0);
    return endTime - startTime;
  }

  public Optional<String> getStdin() throws IOException {
    return Optional.empty();
  }

  /**
   * Returns a description of the shell command args associated with this {@code IsolatedShellStep}.
   */
  public String createDescriptionFromCmd(
      ImmutableList<String> command, ImmutableMap<String, String> environmentVariables) {
    // Get environment variables for this command as VAR1=val1 VAR2=val2... etc., with values
    // quoted as necessary.
    Iterable<String> env =
        Iterables.transform(
            environmentVariables.entrySet(),
            e -> String.format("%s=%s", e.getKey(), Escaper.escapeAsBashString(e.getValue())));

    // Quote the arguments to the shell command as needed (this applies to $0 as well
    // e.g. if we run '/path/a b.sh' quoting is needed).
    Iterable<String> cmd = Iterables.transform(command, Escaper.SHELL_ESCAPER::apply);

    String shellCommand = Joiner.on(" ").join(Iterables.concat(env, cmd));
    // This is what the user might type in a shell to set the working directory correctly. The (...)
    // syntax introduces a subshell in which the command is only executed if cd was successful.
    // Note that we shouldn't add a special case for workingDirectory==null, because we always
    // resolve symbolic links in this case, and the default PWD might leave symbolic links
    // unresolved.  We try to make PWD match, and cd sets PWD.
    return String.format(
        "(cd %s && %s)", Escaper.escapeAsBashString(workingDirectory), shellCommand);
  }

  /**
   * Returns the environment variables to include when running this {@link IsolatedShellStep}.
   *
   * <p>By default, this method returns an empty map.
   *
   * @param platform that may be useful when determining environment variables to include.
   */
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return ImmutableMap.of();
  }

  /**
   * @param verbosity is provided in case that affects what should be printed.
   * @return whether the stdout of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stdout will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  public boolean shouldPrintStdout(Verbosity verbosity) {
    return verbosity.shouldPrintOutput();
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public String getStdout() {
    Preconditions.checkState(
        this.stdout.isPresent(),
        "stdout was not set: shouldPrintStdout() must return false and execute() must "
            + "have been invoked");
    return this.stdout.get();
  }

  /**
   * @return whether the stderr of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stderr will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  public boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintOutput();
  }

  /**
   * @return the stderr of this ShellCommand or throws an exception if the stderr was not recorded
   */
  public String getStderr() {
    Preconditions.checkState(
        this.stderr.isPresent(),
        "stderr was not set: shouldPrintStdErr() must return false and execute() must "
            + "have been invoked");
    return this.stderr.get();
  }

  /** @return an optional timeout to apply to the step. */
  public Optional<Long> getTimeout() {
    return Optional.empty();
  }

  /**
   * @return an optional timeout handler {@link Function} to do something before the process is
   *     killed.
   */
  @SuppressWarnings("unused")
  public Optional<Consumer<Process>> getTimeoutHandler(IsolatedExecutionContext context) {
    return Optional.empty();
  }

  public boolean isWithDownwardApi() {
    return withDownwardApi;
  }

  public Path getWorkingDirectory() {
    return workingDirectory;
  }
}
