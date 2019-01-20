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

package com.facebook.buck.util;

import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public interface ProcessExecutor {
  /**
   * Convenience method for {@link #launchAndExecute(ProcessExecutorParams, Set, Optional, Optional,
   * Optional)} with boolean values set to {@code false} and optional values set to absent.
   */
  Result launchAndExecute(ProcessExecutorParams params) throws InterruptedException, IOException;

  Result launchAndExecute(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws InterruptedException, IOException;

  /**
   * Launches then executes a process with the specified {@code params}.
   *
   * <p>If {@code options} contains {@link Option#PRINT_STD_OUT}, then the stdout of the process
   * will be written directly to the stdout passed to the constructor of this executor. Otherwise,
   * the stdout of the process will be made available via {@link Result#getStdout()}.
   *
   * <p>If {@code options} contains {@link Option#PRINT_STD_ERR}, then the stderr of the process
   * will be written directly to the stderr passed to the constructor of this executor. Otherwise,
   * the stderr of the process will be made available via {@link Result#getStderr()}.
   */
  Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException;

  Result launchAndExecute(
      ProcessExecutorParams params,
      ImmutableMap<String, String> context,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException;

  /** Launches a {@link Process} given {@link ProcessExecutorParams}. */
  LaunchedProcess launchProcess(ProcessExecutorParams params) throws IOException;

  LaunchedProcess launchProcess(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws IOException;

  /** Terminates a process previously returned by {@link #launchProcess(ProcessExecutorParams)}. */
  void destroyLaunchedProcess(LaunchedProcess launchedProcess);

  /**
   * Blocks while waiting for a process previously returned by {@link
   * #launchProcess(ProcessExecutorParams)} to exit, then returns the exit code of the process.
   *
   * <p>After this method returns, the {@code launchedProcess} can no longer be passed to any
   * methods of this object.
   */
  Result waitForLaunchedProcess(LaunchedProcess launchedProcess) throws InterruptedException;

  /** As {@link #waitForLaunchedProcess(LaunchedProcess)} but with a timeout in milliseconds. */
  Result waitForLaunchedProcessWithTimeout(
      LaunchedProcess launchedProcess, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException;

  /**
   * Options for {@link ProcessExecutor#launchAndExecute(ProcessExecutorParams, Set, Optional,
   * Optional, Optional)}.
   */
  enum Option {
    PRINT_STD_OUT,
    PRINT_STD_ERR,

    /** If set, will not highlight output to stdout or stderr when printing. */
    EXPECTING_STD_OUT,
    EXPECTING_STD_ERR,

    /**
     * If set, do not write output to stdout or stderr. However, if the process exits with a
     * non-zero exit code, then the stdout and stderr from the process will be presented to the user
     * to aid in debugging.
     */
    IS_SILENT,
  }

  /** Represents a running process returned by {@link #launchProcess(ProcessExecutorParams)}. */
  interface LaunchedProcess {
    /** @return false if process is killed, or true if it is alive. */
    boolean isAlive();

    /**
     * Output stream that maps into stdin of the process. You'd write into process' stdin using it.
     */
    OutputStream getOutputStream();

    /** Input stream that maps into stdout of the process. You'd read process' stdout from it. */
    InputStream getInputStream();

    /** Input stream that maps into stderr of the process. You'd read process' stderr from it. */
    InputStream getErrorStream();
  }

  /**
   * Wraps a {@link Process} and exposes only its I/O streams, so callers have to pass it back to
   * this class.
   */
  @VisibleForTesting
  class LaunchedProcessImpl implements LaunchedProcess {
    public final Process process;

    public LaunchedProcessImpl(Process process) {
      this.process = process;
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }
  }

  /**
   * Values from the result of {@link ProcessExecutor#launchAndExecute(ProcessExecutorParams, Set,
   * Optional, Optional, Optional)}.
   */
  class Result {

    private final int exitCode;
    private final boolean timedOut;
    private final Optional<String> stdout;
    private final Optional<String> stderr;

    public Result(
        int exitCode, boolean timedOut, Optional<String> stdout, Optional<String> stderr) {
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public Result(int exitCode, String stdout, String stderr) {
      this(exitCode, /* timedOut */ false, Optional.of(stdout), Optional.of(stderr));
    }

    public Result(int exitCode) {
      this(exitCode, /* timedOut */ false, Optional.empty(), Optional.empty());
    }

    public int getExitCode() {
      return exitCode;
    }

    public boolean isTimedOut() {
      return timedOut;
    }

    public Optional<String> getStdout() {
      return stdout;
    }

    public Optional<String> getStderr() {
      return stderr;
    }

    public String getMessageForUnexpectedResult(String subject) {
      return getMessageForResult(subject + " finished with unexpected result");
    }

    public String getMessageForResult(String message) {
      return String.format(
          "%s:\n" + "exit code: %s\n" + "stdout:\n" + "%s" + "\n" + "stderr:\n" + "%s" + "\n",
          message,
          getExitCode(),
          MoreStrings.truncatePretty(getStdout().orElse("")),
          MoreStrings.truncatePretty(getStderr().orElse("")));
    }
  }

  /** Makes a clone of this process executor with the stdout and stderr streams overridden. */
  ProcessExecutor cloneWithOutputStreams(PrintStream stdOutStream, PrintStream stdErrStream);
}
