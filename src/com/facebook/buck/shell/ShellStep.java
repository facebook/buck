/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
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
import javax.annotation.Nullable;

public abstract class ShellStep implements Step {
  private static final Logger LOG = Logger.get(ShellStep.class);
  private static final OperatingSystemMXBean OS_JMX = ManagementFactory.getOperatingSystemMXBean();

  /** Defined lazily by {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)}. */
  @Nullable private ImmutableList<String> shellCommandArgs;

  /**
   * If specified, working directory will be different from build cell root. This should be relative
   * to the build cell root.
   */
  protected final Path workingDirectory;

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

  protected ShellStep(Path workingDirectory) {
    this.workingDirectory = Objects.requireNonNull(workingDirectory);
    this.stdout = Optional.empty();
    this.stderr = Optional.empty();

    if (!workingDirectory.isAbsolute()) {
      LOG.info("Working directory is not absolute: %s", workingDirectory);
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws InterruptedException, IOException {
    ImmutableList<String> command = getShellCommand(context);
    if (command.size() == 0) {
      return StepExecutionResult.of(0);
    }

    // Kick off a Process in which this ShellCommand will be run.
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setCommand(command);
    Map<String, String> environment = new HashMap<>();
    setProcessEnvironment(context, environment, workingDirectory.toString());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Environment: %s", Joiner.on(" ").withKeyValueSeparator('=').join(environment));
    }
    builder.setEnvironment(ImmutableMap.copyOf(environment));
    builder.setDirectory(context.getBuildCellRootPath().resolve(workingDirectory));

    Optional<String> stdin = getStdin(context);
    if (stdin.isPresent()) {
      builder.setRedirectInput(ProcessBuilder.Redirect.PIPE);
    }

    double initialLoad = OS_JMX.getSystemLoadAverage();
    startTime = System.currentTimeMillis();
    int exitCode = launchAndInteractWithProcess(context, builder.build());
    endTime = System.currentTimeMillis();
    double endLoad = OS_JMX.getSystemLoadAverage();

    if (LOG.isDebugEnabled()) {
      boolean hasOutput =
          (stdout.isPresent() && !stdout.get().isEmpty())
              || (stderr.isPresent() && !stderr.get().isEmpty());
      String outputFormat = hasOutput ? "\nstdout:\n%s\nstderr:\n%s\n" : " (no output)%s%s";
      LOG.debug(
          "%s: exit code: %d. os load (before, after): (%f, %f). CPU count: %d." + outputFormat,
          shellCommandArgs,
          exitCode,
          initialLoad,
          endLoad,
          OS_JMX.getAvailableProcessors(),
          stdout.orElse(""),
          stderr.orElse(""));
    }

    return StepExecutionResult.of(exitCode, stderr);
  }

  @VisibleForTesting
  void setProcessEnvironment(
      ExecutionContext context, Map<String, String> environment, String workDir) {

    // Replace environment with client environment.
    environment.clear();
    environment.putAll(context.getEnvironment());

    // Make sure the special PWD variable matches the working directory
    // of the process (unless otherwise set).
    environment.put("PWD", workDir);

    // Add extra environment variables for step, if appropriate.
    if (!getEnvironmentVariables(context).isEmpty()) {
      environment.putAll(getEnvironmentVariables(context));
    }
  }

  /** @return the exit code interpreted from the {@code result}. */
  @SuppressWarnings("unused")
  protected int getExitCodeFromResult(ExecutionContext context, ProcessExecutor.Result result) {
    return result.getExitCode();
  }

  @VisibleForTesting
  int launchAndInteractWithProcess(ExecutionContext context, ProcessExecutorParams params)
      throws InterruptedException, IOException {
    ImmutableSet.Builder<Option> options = ImmutableSet.builder();

    addOptions(options);

    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutor.Result result =
        executor.launchAndExecute(
            params, options.build(), getStdin(context), getTimeout(), getTimeoutHandler(context));
    stdout = result.getStdout();
    stderr = result.getStderr();

    Verbosity verbosity = context.getVerbosity();
    if (stdout.isPresent()
        && !stdout.get().isEmpty()
        && (result.getExitCode() != 0 || shouldPrintStdout(verbosity))) {
      context.postEvent(ConsoleEvent.info("%s", stdout.get()));
    }
    if (stderr.isPresent()
        && !stderr.get().isEmpty()
        && (result.getExitCode() != 0 || shouldPrintStderr(verbosity))) {
      context.postEvent(ConsoleEvent.warning("%s", stderr.get()));
    }

    return getExitCodeFromResult(context, result);
  }

  protected void addOptions(ImmutableSet.Builder<Option> options) {
    options.add(Option.IS_SILENT);
  }

  public long getDuration() {
    Preconditions.checkState(startTime > 0);
    Preconditions.checkState(endTime > 0);
    return endTime - startTime;
  }

  /**
   * This method is idempotent.
   *
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(ExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
      if (shellCommandArgs.size() > 0) {
        LOG.debug("Command: %s", Joiner.on(" ").join(shellCommandArgs));
      }
    }
    return shellCommandArgs;
  }

  protected ImmutableList<String> getShellCommandArgsForDescription(ExecutionContext context) {
    return getShellCommand(context);
  }

  @SuppressWarnings("unused")
  protected Optional<String> getStdin(ExecutionContext context) throws InterruptedException {
    return Optional.empty();
  }

  /** Implementations of this method should not have any observable side-effects. */
  @VisibleForTesting
  protected abstract ImmutableList<String> getShellCommandInternal(ExecutionContext context);

  @Override
  public final String getDescription(ExecutionContext context) {
    // Get environment variables for this command as VAR1=val1 VAR2=val2... etc., with values
    // quoted as necessary.
    Iterable<String> env =
        Iterables.transform(
            getEnvironmentVariables(context).entrySet(),
            e -> String.format("%s=%s", e.getKey(), Escaper.escapeAsBashString(e.getValue())));

    // Quote the arguments to the shell command as needed (this applies to $0 as well
    // e.g. if we run '/path/a b.sh' quoting is needed).
    Iterable<String> cmd =
        Iterables.transform(
            getShellCommandArgsForDescription(context), Escaper.SHELL_ESCAPER::apply);

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
   * Returns the environment variables to include when running this {@link ShellStep}.
   *
   * <p>By default, this method returns an empty map.
   *
   * @param context that may be useful when determining environment variables to include.
   */
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.of();
  }

  /**
   * @param verbosity is provided in case that affects what should be printed.
   * @return whether the stdout of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stdout will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStdout(Verbosity verbosity) {
    return verbosity.shouldPrintOutput();
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdout() {
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
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintOutput();
  }

  /**
   * @return the stderr of this ShellCommand or throws an exception if the stderr was not recorded
   */
  public final String getStderr() {
    Preconditions.checkState(
        this.stderr.isPresent(),
        "stderr was not set: shouldPrintStdErr() must return false and execute() must "
            + "have been invoked");
    return this.stderr.get();
  }

  /** @return an optional timeout to apply to the step. */
  protected Optional<Long> getTimeout() {
    return Optional.empty();
  }

  /**
   * @return an optional timeout handler {@link Function} to do something before the process is
   *     killed.
   */
  @SuppressWarnings("unused")
  protected Optional<Consumer<Process>> getTimeoutHandler(ExecutionContext context) {
    return Optional.empty();
  }
}
