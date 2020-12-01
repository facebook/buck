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

package com.facebook.buck.shell;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.shell.ShellStepDelegate;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
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

public abstract class ShellStep implements Step {
  private static final Logger LOG = Logger.get(ShellStep.class);

  /** Defined lazily by {@link #getShellCommand(StepExecutionContext)}. */
  @Nullable private ImmutableList<String> shellCommandArgs;

  private final ShellStepDelegate shellStepDelegate;

  @VisibleForTesting
  protected ShellStep(ShellStepDelegate shellStepDelegate) {
    this.shellStepDelegate = shellStepDelegate;
  }

  protected ShellStep(Path workingDirectory, boolean withDownwardApi) {
    this(new ShellStepDelegate(workingDirectory, withDownwardApi, LOG));
  }

  protected ShellStep(AbsPath workingDirectory, boolean withDownwardApi) {
    this(workingDirectory.getPath(), withDownwardApi);
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws InterruptedException, IOException {
    return shellStepDelegate.executeCommand(
        context,
        ProjectFilesystemUtils.relativize(
            context.getRuleCellRoot(), context.getBuildCellRootPath()),
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

  @VisibleForTesting
  void setProcessEnvironment(
      StepExecutionContext context,
      Map<String, String> environment,
      String workDir,
      ImmutableMap<String, String> additionalEnvVars) {
    shellStepDelegate.setProcessEnvironment(context, environment, workDir, additionalEnvVars);
  }

  /** @return the exit code interpreted from the {@code result}. */
  protected int getExitCodeFromResult(ProcessExecutor.Result result) {
    return shellStepDelegate.getExitCodeFromResult(result);
  }

  @VisibleForTesting
  ProcessExecutor.Result launchAndInteractWithProcess(
      StepExecutionContext context,
      ProcessExecutorParams params,
      Optional<ProcessExecutor.Stdin> stdin)
      throws InterruptedException, IOException {
    return shellStepDelegate.launchAndInteractWithProcess(
        context,
        params,
        stdin,
        getTimeout(),
        getTimeoutHandler(context),
        shouldPrintStdout(context.getVerbosity()),
        shouldPrintStderr(context.getVerbosity()),
        this::addOptions);
  }

  protected void addOptions(ImmutableSet.Builder<Option> options) {
    shellStepDelegate.addOptions(options);
  }

  public long getDuration() {
    return shellStepDelegate.getDuration();
  }

  /**
   * This method is idempotent.
   *
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(StepExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
      if (shellCommandArgs.size() > 0) {
        LOG.debug("Command: %s", Joiner.on(" ").join(shellCommandArgs));
      }
    }
    return shellCommandArgs;
  }

  protected ImmutableList<String> getShellCommandArgsForDescription(StepExecutionContext context) {
    return getShellCommand(context);
  }

  private void writeStdin(OutputStream stream) throws IOException {
    shellStepDelegate.writeStdin(stream);
  }

  /** Implementations of this method should not have any observable side-effects. */
  @VisibleForTesting
  protected abstract ImmutableList<String> getShellCommandInternal(StepExecutionContext context);

  @Override
  public final String getDescription(StepExecutionContext context) {
    return shellStepDelegate.createDescriptionFromCmd(
        getShellCommandArgsForDescription(context), getEnvironmentVariables(context.getPlatform()));
  }

  /**
   * Returns the environment variables to include when running this {@link ShellStep}.
   *
   * <p>By default, this method returns an empty map.
   *
   * @param platform that may be useful when determining environment variables to include.
   */
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return shellStepDelegate.getEnvironmentVariables(platform);
  }

  /**
   * @param verbosity is provided in case that affects what should be printed.
   * @return whether the stdout of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link StepExecutionContext}. If {@code false}, stdout will only be
   *     printed on error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStdout(Verbosity verbosity) {
    return shellStepDelegate.shouldPrintStdout(verbosity);
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdout() {
    return shellStepDelegate.getStdout();
  }

  /**
   * @return whether the stderr of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link StepExecutionContext}. If {@code false}, stderr will only be
   *     printed on error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return shellStepDelegate.shouldPrintStderr(verbosity);
  }

  /**
   * @return the stderr of this ShellCommand or throws an exception if the stderr was not recorded
   */
  public final String getStderr() {
    return shellStepDelegate.getStderr();
  }

  /** @return an optional timeout to apply to the step. */
  protected Optional<Long> getTimeout() {
    return shellStepDelegate.getTimeout();
  }

  /**
   * @return an optional timeout handler {@link Function} to do something before the process is
   *     killed.
   */
  protected Optional<Consumer<Process>> getTimeoutHandler(StepExecutionContext context) {
    return shellStepDelegate.getTimeoutHandler(context);
  }

  protected boolean isWithDownwardApi() {
    return shellStepDelegate.isWithDownwardApi();
  }

  protected Path getWorkingDirectory() {
    return shellStepDelegate.getWorkingDirectory();
  }
}
