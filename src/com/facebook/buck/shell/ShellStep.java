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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

public abstract class ShellStep implements Step {

  /** Defined lazily by {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)}. */
  private ImmutableList<String> shellCommandArgs;

  /** If specified, the value to return for {@link #getDescription(com.facebook.buck.step.ExecutionContext)}. */
  @Nullable
  private final String description;

  /** If specified, working directory will be different from project root. **/
  @Nullable
  protected final File workingDirectory;

  /**
   * This is set if {@link #shouldRecordStdout()} returns {@code true} when the command is
   * executed.
   */
  @Nullable
  private String stdOut;

  /**
   * Creates a new {@link ShellStep} using the default logic to return a description from
   * {@link #getDescription(com.facebook.buck.step.ExecutionContext)}.
   */
  protected ShellStep() {
    this(null, null);
  }

  /**
   * @param description to return as the value of {@link #getDescription(com.facebook.buck.step.ExecutionContext)}. By
   *     default, {@link #getDescription(com.facebook.buck.step.ExecutionContext)} returns a formatted version of the value
   *     returned by {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)}; however, if
   *     {@link #setup(com.facebook.buck.step.ExecutionContext)} is overridden, then it is likely that
   *     {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)} cannot return a value until
   *     {@link #execute(com.facebook.buck.step.ExecutionContext)} has been invoked. Because
   *     {@link #getDescription(com.facebook.buck.step.ExecutionContext)} may be invoked before
   *     {@link #execute(com.facebook.buck.step.ExecutionContext)} (usually for logging purposes), this constructor should
   *     be used if {@link #getShellCommandInternal(com.facebook.buck.step.ExecutionContext)} cannot be invoked before
   *     {@link #setup(com.facebook.buck.step.ExecutionContext)}.
   */
  protected ShellStep(String description) {
    this(Preconditions.checkNotNull(description), null);
  }

  protected ShellStep(File workingDirectory) {
    this(null, Preconditions.checkNotNull(workingDirectory));
  }

  @VisibleForTesting
  ShellStep(@Nullable String description, @Nullable File workingDirectory) {
    this.description = description;
    this.workingDirectory = workingDirectory;
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

  /**
   * @return whether stdout should be recorded when this command is executed. If this returns
   *     {@code true}, then the stdout will be available via {@link #getStdOut()}.
   */
  protected boolean shouldRecordStdout() {
    return false;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      setup(context);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    // Kick off a Process in which this ShellCommand will be run.
    ProcessBuilder processBuilder = new ProcessBuilder(getShellCommand(context));

    // Add environment variables, if appropriate.
    if (!getEnvironmentVariables().isEmpty()) {
      Map<String, String> environment = processBuilder.environment();
      environment.putAll(getEnvironmentVariables());
    }

    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory);
    } else {
      processBuilder.directory(context.getProjectDirectoryRoot());
    }

    Process process;
    try {
      process = processBuilder.start();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    return interactWithProcess(context, process);
  }

  @VisibleForTesting
  int interactWithProcess(ExecutionContext context, Process process) {
    boolean shouldRecordStdOut = shouldRecordStdout();
    boolean shouldPrintStdErr = shouldPrintStdErr(context);
    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutor.Result result = executor.execute(process,
        shouldRecordStdOut,
        shouldPrintStdErr,
        context.getVerbosity() == Verbosity.SILENT);
    if (shouldRecordStdOut) {
      this.stdOut = result.getStdOut();
    }
    return result.getExitCode();
  }

  /**
   * This method is idempotent.
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(ExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
    }
    return shellCommandArgs;
  }

  /**
   * Implementations of this method should not have any observable side-effects. If any I/O needs to
   * be done to produce the shell command arguments, then it should be done in
   * {@link #setup(ExecutionContext)}.
   */
  @VisibleForTesting
  protected abstract ImmutableList<String> getShellCommandInternal(ExecutionContext context);

  @Override
  public final String getDescription(ExecutionContext context) {
    if (description == null) {

      // Get environment variables for this command as VAR1=val1 VAR2=val2... etc., with values
      // quoted as necessary.
      Iterable<String> env = Iterables.transform(getEnvironmentVariables().entrySet(),
          new Function<Entry<String, String>, String>() {
            @Override
            public String apply(Entry<String, String> e) {
              return String.format("%s=%s", e.getKey(), Escaper.escapeAsBashString(e.getValue()));
            }
      });

      // Quote the arguments to the shell command as needed (this applies to $0 as well
      // e.g. if we run '/path/a b.sh' quoting is needed).
      Iterable<String> cmd = Iterables.transform(getShellCommand(context), Escaper.BASH_ESCAPER);

      String shellCommand = Joiner.on(" ").join(Iterables.concat(env, cmd));
      if (getWorkingDirectory() == null) {
        return shellCommand;
      } else {
        // If the ShellCommand has a specific working directory, set through ProcessBuilder, then
        // this is what the user might type in a shell to get the same behavior. The (...) syntax
        // introduces a subshell in which the command is only executed if cd was successful.
        return String.format("(cd %s && %s)",
            Escaper.escapeAsBashString(workingDirectory.getPath()),
            shellCommand);
      }
    } else {
      return description;
    }
  }

  /**
   * @return whether the stderr of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stderr will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStdErr(ExecutionContext context) {
    return context.getVerbosity().shouldPrintOutput();
  }

  /** By default, this method returns an empty map. */
  public ImmutableMap<String, String> getEnvironmentVariables() {
    return ImmutableMap.of();
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdOut() {
    Preconditions.checkNotNull(this.stdOut, "stdout was not set: " +
    		"shouldRecordStdout() must return true and execute() must have been invoked");
    return this.stdOut;
  }

  @VisibleForTesting
  final void setStdOut(String stdOut) {
    this.stdOut = stdOut;
  }

  /**
   * This method will be invoked exactly once at the start of {@link #execute(ExecutionContext)}.
   * Most shell commands will not need to override this method, as all the information that is
   * needed to return a value for {@link #getShellCommand(ExecutionContext)} should be passed into
   * the constructor. In rare cases, some information to produce the shell command arguments will
   * not be available until runtime. In such rare cases, such logic should be added here.
   */
  protected void setup(@SuppressWarnings("unused") ExecutionContext context) throws IOException {

  }
}
