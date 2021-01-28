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

import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public interface ProcessExecutor {

  /**
   * Convenience method for {@link #launchAndExecute(ProcessExecutorParams, Set, Optional, Optional,
   * Optional)} with context map set to empty map and optional values set to absent.
   */
  default Result launchAndExecute(ProcessExecutorParams params)
      throws InterruptedException, IOException {
    return launchAndExecute(params, ImmutableMap.of());
  }

  /**
   * Convenience method for {@link #launchAndExecute(ProcessExecutorParams, ImmutableMap, Set,
   * Optional, Optional, Optional)} with options set to empty set and optional values set to absent.
   */
  default Result launchAndExecute(
      ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws InterruptedException, IOException {
    return launchAndExecute(
        params,
        context,
        ImmutableSet.of(),
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());
  }

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
  default Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return launchAndExecute(params, ImmutableMap.of(), options, stdin, timeOutMs, timeOutHandler);
  }

  default Result launchAndExecute(
      ProcessExecutorParams params,
      ImmutableMap<String, String> context,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return execute(launchProcess(params, context), options, stdin, timeOutMs, timeOutHandler);
  }

  /** Launches a {@link Process} given {@link ProcessExecutorParams}. */
  default LaunchedProcess launchProcess(ProcessExecutorParams params) throws IOException {
    return launchProcess(params, ImmutableMap.of());
  }

  LaunchedProcess launchProcess(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws IOException;

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

  /** Executes launched process */
  Result execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException;

  /** Makes a clone of this process executor with the stdout and stderr streams overridden. */
  ProcessExecutor cloneWithOutputStreams(PrintStream stdOutStream, PrintStream stdErrStream);

  /** Creates a {@code ProcessExecutor} that supports Downward API */
  ProcessExecutor withDownwardAPI(
      DownwardApiProcessExecutorFactory factory,
      NamedPipeEventHandlerFactory namedPipeEventHandlerFactory,
      IsolatedEventBus buckEventBus,
      String actionId,
      Clock clock);

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
  interface LaunchedProcess extends AutoCloseable {

    /** @return false if process is killed, or true if it is alive. */
    boolean isAlive();

    ImmutableList<String> getCommand();

    /**
     * Output stream that maps into stdin of the process. You'd write into process' stdin using it.
     */
    OutputStream getStdin();

    /** Input stream that maps into stdout of the process. You'd read process' stdout from it. */
    InputStream getStdout();

    /** Input stream that maps into stderr of the process. You'd read process' stderr from it. */
    InputStream getStderr();

    /** Terminates a running process. */
    @Override
    void close();
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
    public final ImmutableList<String> command;

    public Result(
        int exitCode,
        boolean timedOut,
        Optional<String> stdout,
        Optional<String> stderr,
        ImmutableList<String> command) {
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.stdout = stdout;
      this.stderr = stderr;
      this.command = command;
    }

    public Result(int exitCode, String stdout, String stderr, ImmutableList<String> command) {
      this(exitCode, /* timedOut */ false, Optional.of(stdout), Optional.of(stderr), command);
    }

    public Result(int exitCode, ImmutableList<String> command) {
      this(exitCode, /* timedOut */ false, Optional.empty(), Optional.empty(), command);
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

    public ImmutableList<String> getCommand() {
      return command;
    }

    public String getMessageForUnexpectedResult(String subject) {
      return getMessageForResult(subject + " finished with unexpected result");
    }

    public String getMessageForResult(String message) {
      return String.format(
          "%s:\n"
              + "exit code: %s\n"
              + "command: %s\n"
              + "stdout:\n"
              + "%s"
              + "\n"
              + "stderr:\n"
              + "%s"
              + "\n",
          message,
          getExitCode(),
          MoreStrings.truncatePretty(getCommand().toString()),
          MoreStrings.truncatePretty(getStdout().orElse("")),
          MoreStrings.truncatePretty(getStderr().orElse("")));
    }
  }

  /** Callback passed by callers to write input to a launched process. */
  interface Stdin {

    void writeTo(OutputStream stdin) throws IOException;

    /** @return a {@Stdin} wrapping a constant {@link String} */
    static Stdin of(String input) {
      return stdin -> stdin.write(input.getBytes(StandardCharsets.UTF_8));
    }
  }
}
