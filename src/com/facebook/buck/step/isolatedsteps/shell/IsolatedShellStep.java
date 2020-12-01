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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * {@link IsolatedStep} that executes a shell command.
 *
 * <p>Subclasses are expected to implement {@link
 * #getShellCommandInternal(IsolatedExecutionContext)} to define what command to execute in the
 * shell.
 *
 * <p>See also {@link com.facebook.buck.shell.ShellStep}, which serves the same function but is a
 * regular {@link Step} that uses a {@link
 * com.facebook.buck.core.build.execution.context.StepExecutionContext} instead of an {@link
 * IsolatedExecutionContext}.
 */
public abstract class IsolatedShellStep extends IsolatedStep {
  private static final Logger LOG = Logger.get(IsolatedShellStep.class);

  /** Defined lazily by {@link #getShellCommand(IsolatedExecutionContext)}. */
  @Nullable private ImmutableList<String> shellCommandArgs;

  private final ShellStepDelegate delegate;
  private final RelPath cellPath;

  protected IsolatedShellStep(Path workingDirectory, RelPath cellPath, boolean withDownwardApi) {
    this.cellPath = cellPath;
    this.delegate = new ShellStepDelegate(workingDirectory, withDownwardApi, LOG);
  }

  protected IsolatedShellStep(AbsPath workingDirectory, RelPath cellPath, boolean withDownwardApi) {
    this(workingDirectory.getPath(), cellPath, withDownwardApi);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws InterruptedException, IOException {
    return delegate.executeCommand(
        context,
        cellPath,
        getShellCommand(context),
        getEnvironmentVariables(context.getPlatform()),
        Optional.of(this::writeStdin),
        getTimeout(),
        getTimeoutHandler(context),
        shouldPrintStdout(context.getVerbosity()),
        shouldPrintStderr(context.getVerbosity()),
        this::addOptions,
        this::getExitCodeFromResult);
  }

  /**
   * Sets the environment variables from the given context, the given working directory, and any
   * additional environment variables onto the given environment. Not side-effect free.
   */
  void setProcessEnvironment(
      IsolatedExecutionContext context,
      Map<String, String> environment,
      String workDir,
      ImmutableMap<String, String> additionalEnvVars) {
    delegate.setProcessEnvironment(context, environment, workDir, additionalEnvVars);
  }

  /** @return the exit code interpreted from the {@code result}. */
  protected int getExitCodeFromResult(ProcessExecutor.Result result) {
    return delegate.getExitCodeFromResult(result);
  }

  /**
   * Launches and executes a process in the given context with the given process executor params.
   * Posts events if applicable.
   */
  ProcessExecutor.Result launchAndInteractWithProcess(
      IsolatedExecutionContext context,
      ProcessExecutorParams params,
      Optional<ProcessExecutor.Stdin> stdin,
      Optional<Long> timeout,
      Optional<Consumer<Process>> timeoutHandler,
      boolean shouldPrintStdOut,
      boolean shouldPrintStdErr,
      Consumer<ImmutableSet.Builder<ProcessExecutor.Option>> addOptions)
      throws InterruptedException, IOException {
    return delegate.launchAndInteractWithProcess(
        context,
        params,
        stdin,
        timeout,
        timeoutHandler,
        shouldPrintStdOut,
        shouldPrintStdErr,
        addOptions);
  }

  /** Adds relevant {@link ProcessExecutor.Option} to the given options. Not side-effect free. */
  protected void addOptions(ImmutableSet.Builder<ProcessExecutor.Option> options) {
    delegate.addOptions(options);
  }

  /**
   * Returns the time taken for launching and executing the process that runs the shell command args
   * associated with this {@code IsolatedShellStep}. Not pure.
   */
  public long getDuration() {
    return delegate.getDuration();
  }

  /**
   * This method is idempotent.
   *
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(IsolatedExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
      if (shellCommandArgs.size() > 0) {
        LOG.debug("Command: %s", Joiner.on(" ").join(shellCommandArgs));
      }
    }
    return shellCommandArgs;
  }

  /**
   * Returns a description of the shell command args associated with this {@code IsolatedShellStep}.
   */
  protected ImmutableList<String> getShellCommandArgsForDescription(
      IsolatedExecutionContext context) {
    return getShellCommand(context);
  }

  public void writeStdin(OutputStream stream) throws IOException {
    delegate.writeStdin(stream);
  }

  @Override
  public final String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return delegate.createDescriptionFromCmd(
        getShellCommandArgsForDescription(context), getEnvironmentVariables(context.getPlatform()));
  }

  /**
   * Returns the environment variables to include when running this {@link IsolatedShellStep}.
   *
   * <p>By default, this method returns an empty map.
   *
   * @param platform that may be useful when determining environment variables to include.
   */
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return delegate.getEnvironmentVariables(platform);
  }

  /**
   * @param verbosity is provided in case that affects what should be printed.
   * @return whether the stdout of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stdout will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  public boolean shouldPrintStdout(Verbosity verbosity) {
    return delegate.shouldPrintStdout(verbosity);
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdout() {
    return delegate.getStdout();
  }

  /**
   * @return whether the stderr of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stderr will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  public boolean shouldPrintStderr(Verbosity verbosity) {
    return delegate.shouldPrintStderr(verbosity);
  }

  /**
   * @return the stderr of this ShellCommand or throws an exception if the stderr was not recorded
   */
  public final String getStderr() {
    return delegate.getStderr();
  }

  /** @return an optional timeout to apply to the step. */
  public Optional<Long> getTimeout() {
    return delegate.getTimeout();
  }

  /**
   * @return an optional timeout handler {@link Function} to do something before the process is
   *     killed.
   */
  public Optional<Consumer<Process>> getTimeoutHandler(IsolatedExecutionContext context) {
    return delegate.getTimeoutHandler(context);
  }

  public boolean isWithDownwardApi() {
    return delegate.isWithDownwardApi();
  }

  public Path getWorkingDirectory() {
    return delegate.getWorkingDirectory();
  }

  /** Implementations of this method should not have any observable side-effects. */
  protected abstract ImmutableList<String> getShellCommandInternal(
      IsolatedExecutionContext context);
}
