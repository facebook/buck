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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

public abstract class ShellStep implements Step {

  private static final Logger LOG = Logger.get(ShellStep.class);
  private static final OperatingSystemMXBean OS_JMX = ManagementFactory.getOperatingSystemMXBean();

  /** Defined lazily by {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)}. */
  @Nullable
  private ImmutableList<String> shellCommandArgs;

  /** If specified, working directory will be different from project root. **/
  @Nullable
  protected final File workingDirectory;

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

  protected ShellStep() {
    this(/* workingDirectory */ null);
  }

  protected ShellStep(@Nullable File workingDirectory) {
    this.workingDirectory = workingDirectory;
    this.stdout = Optional.absent();
    this.stderr = Optional.absent();
  }

  /**
   * Get the working directory for this command.
   * @return working directory specified on construction
   *         ({@code null} if project directory will be used).
   */
  @Nullable
  @VisibleForTesting
  public File getWorkingDirectory() {
    return workingDirectory;
  }

  private File getFinalWorkingDirectory(ExecutionContext context) {
    if (workingDirectory != null) {
      return workingDirectory;
    }

    return context.getProjectDirectoryRoot().toAbsolutePath().toFile();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    // Kick off a Process in which this ShellCommand will be run.
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();

    File workDir = getFinalWorkingDirectory(context);

    builder.setCommand(getShellCommand(context));
    Map<String, String> environment = Maps.newHashMap();
    setProcessEnvironment(context, environment, workDir);
    builder.setEnvironment(environment);
    builder.setDirectory(workDir);

    Optional<String> stdin = getStdin(context);
    if (stdin.isPresent()) {
      builder.setRedirectInput(ProcessBuilder.Redirect.PIPE);
    }

    int exitCode;
    double initialLoad = OS_JMX.getSystemLoadAverage();
    try {
      startTime = System.currentTimeMillis();
      exitCode = launchAndInteractWithProcess(context, builder.build());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      exitCode = 1;
    }

    endTime = System.currentTimeMillis();
    double endLoad = OS_JMX.getSystemLoadAverage();

    LOG.debug(
        "%s: exit code: %d. os load (before, after): (%f, %f). CPU count: %d",
        shellCommandArgs,
        exitCode,
        initialLoad,
        endLoad,
        OS_JMX.getAvailableProcessors());

    return exitCode;
  }

  @VisibleForTesting
  void setProcessEnvironment(
      ExecutionContext context,
      Map<String, String> environment,
      File workDir) {

    // Replace environment with client environment.
    environment.clear();
    environment.putAll(context.getEnvironment());

    // Make sure the special PWD variable matches the working directory
    // of the process (unless otherwise set).
    environment.put("PWD", workDir.toString());

    // Add extra environment variables for step, if appropriate.
    if (!getEnvironmentVariables(context).isEmpty()) {
      environment.putAll(getEnvironmentVariables(context));
    }
  }

  /**
   * @return the exit code interpreted from the {@code result}.
   */
  @SuppressWarnings("unused")
  protected int getExitCodeFromResult(ExecutionContext context, ProcessExecutor.Result result) {
    return result.getExitCode();
  }

  @VisibleForTesting
  int launchAndInteractWithProcess(ExecutionContext context, ProcessExecutorParams params)
      throws InterruptedException, IOException {
    ImmutableSet.Builder<Option> options = ImmutableSet.builder();

    addOptions(context, options);

    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutor.Result result = executor.launchAndExecute(
        params,
        options.build(),
        getStdin(context),
        getTimeout(),
        getTimeoutHandler(context));
    stdout = result.getStdout();
    stderr = result.getStderr();

    Verbosity verbosity = context.getVerbosity();
    if (stdout.isPresent() && !stdout.get().isEmpty() && shouldPrintStdout(verbosity)) {
      context.postEvent(ConsoleEvent.info("%s", stdout.get()));
    }
    if (stderr.isPresent() && !stderr.get().isEmpty() && shouldPrintStderr(verbosity)) {
      context.postEvent(ConsoleEvent.warning("%s", stderr.get()));
    }

    return getExitCodeFromResult(context, result);
  }

  protected void addOptions(
      ExecutionContext context,
      ImmutableSet.Builder<Option> options) {
    if (shouldFlushStdOutErrAsProgressIsMade(context.getVerbosity())) {
      options.add(Option.PRINT_STD_OUT);
      options.add(Option.PRINT_STD_ERR);
    }
    if (context.getVerbosity() == Verbosity.SILENT) {
      options.add(Option.IS_SILENT);
    }
  }

  public long getDuration() {
    Preconditions.checkState(startTime > 0);
    Preconditions.checkState(endTime > 0);
    return endTime - startTime;
  }

  /**
   * This method is idempotent.
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(ExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
      LOG.debug("Command: %s", Joiner.on(" ").join(shellCommandArgs));
    }
    return shellCommandArgs;
  }

  @SuppressWarnings("unused")
  protected Optional<String> getStdin(ExecutionContext context) {
    return Optional.absent();
  }

  /**
   * Implementations of this method should not have any observable side-effects.
   */
  @VisibleForTesting
  protected abstract ImmutableList<String> getShellCommandInternal(ExecutionContext context);

  @Override
  public final String getDescription(ExecutionContext context) {
    // Get environment variables for this command as VAR1=val1 VAR2=val2... etc., with values
    // quoted as necessary.
    Iterable<String> env = Iterables.transform(getEnvironmentVariables(context).entrySet(),
        new Function<Entry<String, String>, String>() {
          @Override
          public String apply(Entry<String, String> e) {
            return String.format("%s=%s", e.getKey(), Escaper.escapeAsBashString(e.getValue()));
          }
    });

    // Quote the arguments to the shell command as needed (this applies to $0 as well
    // e.g. if we run '/path/a b.sh' quoting is needed).
    Iterable<String> cmd = Iterables.transform(getShellCommand(context), Escaper.SHELL_ESCAPER);

    String shellCommand = Joiner.on(" ").join(Iterables.concat(env, cmd));
    // This is what the user might type in a shell to set the working directory correctly. The (...)
    // syntax introduces a subshell in which the command is only executed if cd was successful.
    // Note that we shouldn't add a special case for workingDirectory==null, because we always
    // resolve symbolic links in this case, and the default PWD might leave symbolic links
    // unresolved.  We try to make PWD match, and cd sets PWD.
    return String.format("(cd %s && %s)",
        Escaper.escapeAsBashString(getFinalWorkingDirectory(context).getPath()),
        shellCommand);
  }

  /**
   * Returns the environment variables to include when running this {@link ShellStep}.
   * <p>
   * By default, this method returns an empty map.
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
    return false;
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdout() {
    Preconditions.checkState(
        this.stdout.isPresent(),
        "stdout was not set: shouldPrintStdout() must return false and execute() must " +
        "have been invoked");
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
        "stderr was not set: shouldPrintStdErr() must return false and execute() must " +
        "have been invoked");
    return this.stderr.get();
  }

  /**
   * By default, the output written to stdout and stderr will be buffered into individual
   * {@link ByteArrayOutputStream}s and then converted into strings for easier consumption. This
   * means that output from both streams that would normally be interleaved will now be displayed
   * separately.
   * <p>
   * To disable this behavior and print to stdout and stderr directly, this method should be
   * overridden to return {@code true}.
   */
  @SuppressWarnings("unused")
  protected boolean shouldFlushStdOutErrAsProgressIsMade(Verbosity verbosity) {
    return false;
  }

  /**
   * @return an optional timeout to apply to the step.
   */
  protected Optional<Long> getTimeout() {
    return Optional.absent();
  }

  /**
   * @return an optional timeout handler {@link Function} to do something before the process is
   * killed.
   */
  @SuppressWarnings("unused")
  protected Optional<Function<Process, Void>> getTimeoutHandler(ExecutionContext context) {
    return Optional.absent();
  }
}
