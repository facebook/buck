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

package com.facebook.buck.util;

import com.facebook.buck.log.Logger;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Set;

/**
 * Executes a {@link Process} and blocks until it is finished.
 */
public class ProcessExecutor {

  private static final Logger LOG = Logger.get(ProcessExecutor.class);

  /**
   * Options for {@link ProcessExecutor#execute(Process, Set, Optional, Optional)}.
   */
  public static enum Option {
    PRINT_STD_OUT,
    PRINT_STD_ERR,

    /**
     * If set, will not highlight output to stdout or stderr when printing.
     */
    EXPECTING_STD_OUT,
    EXPECTING_STD_ERR,

    /**
     * If set, do not write output to stdout or stderr. However, if the process exits with a
     * non-zero exit code, then the stdout and stderr from the process will be presented to the user
     * to aid in debugging.
     */
    IS_SILENT,
  }

  private final PrintStream stdOutStream;
  private final PrintStream stdErrStream;
  private final Ansi ansi;

  /**
   * Creates a new {@link ProcessExecutor} with the specified parameters used for writing the output
   * of the process.
   */
  public ProcessExecutor(Console console) {
    this.stdOutStream = console.getStdOut();
    this.stdErrStream = console.getStdErr();
    this.ansi = console.getAnsi();
  }

  /**
   * Convenience method for {@link #launchAndExecute(ProcessExecutorParams, Set, Optional, Optional)}
   * with boolean values set to {@code false} and optional values set to absent.
   */
  public Result launchAndExecute(ProcessExecutorParams params)
      throws InterruptedException, IOException {
    return launchAndExecute(
        params,
        ImmutableSet.<Option>of(),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent());
  }

  /**
   * Launches then executes a process with the specified {@code params}.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_OUT}, then the stdout of the process will
   * be written directly to the stdout passed to the constructor of this executor. Otherwise,
   * the stdout of the process will be made available via {@link Result#getStdout()}.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_ERR}, then the stderr of the process will
   * be written directly to the stderr passed to the constructor of this executor. Otherwise,
   * the stderr of the process will be made available via {@link Result#getStderr()}.
   */
  public Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs) throws InterruptedException, IOException {
    return execute(launchProcess(params), options, stdin, timeOutMs);
  }

  /**
   * Launches a {@link java.lang.Process} given {@link ProcessExecutorParams}.
   */
  Process launchProcess(ProcessExecutorParams params) throws IOException {

    ProcessBuilder pb = new ProcessBuilder(params.getCommand());
    if (params.getDirectory().isPresent()) {
      pb.directory(params.getDirectory().get());
    }
    if (params.getEnvironment().isPresent()) {
      pb.environment().clear();
      pb.environment().putAll(params.getEnvironment().get());
    }
    if (params.getRedirectInput().isPresent()) {
      pb.redirectInput(params.getRedirectInput().get());
    }
    if (params.getRedirectOutput().isPresent()) {
      pb.redirectOutput(params.getRedirectOutput().get());
    }
    if (params.getRedirectError().isPresent()) {
      pb.redirectError(params.getRedirectError().get());
    }
    return pb.start();
  }

  /**
   * Convenience method for {@link #execute(Process, Set, Optional, Optional)}
   * with boolean values set to {@code false} and optional values set to absent.
   */
  public Result execute(Process process) throws InterruptedException {
    return execute(
        process,
        ImmutableSet.<Option>of(),
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent());
  }

  /**
   * @return whether the process has finished executing or not.
   */
  private boolean finished(Process process) {
    try {
      process.exitValue();
      return true;
    } catch (IllegalThreadStateException e) {
      return false;
    }
  }

  /**
   * Waits up to {@code millis} milliseconds for the given process to finish.
   */
  private void waitForTimeout(final Process process, long millis) throws InterruptedException {
    Thread waiter =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  process.waitFor();
                } catch (InterruptedException e) {}
              }
            });
    waiter.start();
    waiter.join(millis);
    waiter.interrupt();
    waiter.join();
  }

  /**
   * Executes the specified already-launched process.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_OUT}, then the stdout of the process will
   * be written directly to the stdout passed to the constructor of this executor. Otherwise,
   * the stdout of the process will be made available via {@link Result#getStdout()}.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_ERR}, then the stderr of the process will
   * be written directly to the stderr passed to the constructor of this executor. Otherwise,
   * the stderr of the process will be made available via {@link Result#getStderr()}.
   */
  public Result execute(
      Process process,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs) throws InterruptedException {

    // Read stdout/stderr asynchronously while running a Process.
    // See http://stackoverflow.com/questions/882772/capturing-stdout-when-calling-runtime-exec
    boolean shouldPrintStdOut = options.contains(Option.PRINT_STD_OUT);
    boolean expectingStdOut = options.contains(Option.EXPECTING_STD_OUT);
    @SuppressWarnings("resource")
    PrintStream stdOutToWriteTo = shouldPrintStdOut ?
        stdOutStream : new CapturingPrintStream();
    InputStreamConsumer stdOut = new InputStreamConsumer(
        process.getInputStream(),
        stdOutToWriteTo,
        ansi,
        /* flagOutputWrittenToStream */ !shouldPrintStdOut && !expectingStdOut,
        Optional.<InputStreamConsumer.Handler>absent());

    boolean shouldPrintStdErr = options.contains(Option.PRINT_STD_ERR);
    boolean expectingStdErr = options.contains(Option.EXPECTING_STD_ERR);
    @SuppressWarnings("resource")
    PrintStream stdErrToWriteTo = shouldPrintStdErr ?
        stdErrStream : new CapturingPrintStream();
    InputStreamConsumer stdErr = new InputStreamConsumer(
        process.getErrorStream(),
        stdErrToWriteTo,
        ansi,
        /* flagOutputWrittenToStream */ !shouldPrintStdErr && !expectingStdErr,
        Optional.<InputStreamConsumer.Handler>absent());

    // Consume the streams so they do not deadlock.
    Thread stdOutConsumer = Threads.namedThread("ProcessExecutor (stdOut)", stdOut);
    stdOutConsumer.start();
    Thread stdErrConsumer = Threads.namedThread("ProcessExecutor (stdErr)", stdErr);
    stdErrConsumer.start();

    boolean timedOut = false;

    // Block until the Process completes.
    try {

      // If a stdin string was specific, then write that first.  This shouldn't cause
      // deadlocks, as the stdout/stderr consumers are running in separate threads.
      if (stdin.isPresent()) {
        try (OutputStreamWriter stdinWriter = new OutputStreamWriter(process.getOutputStream())) {
          stdinWriter.write(stdin.get());
        }
      }

      // Wait for the process to complete.  If a timeout was given, we wait up to the timeout
      // for it to finish then force kill it.  If no timeout was given, just wait for it using
      // the regular `waitFor` method.
      if (timeOutMs.isPresent()) {
        waitForTimeout(process, timeOutMs.get());
        if (!finished(process)) {
          timedOut = true;
          process.destroy();
        }
      } else {
        process.waitFor();
      }

      stdOutConsumer.join();
      stdErrConsumer.join();

    } catch (IOException e) {
      // Buck was killed while waiting for the consumers to finish or while writing stdin
      // to the process. This means either the user killed the process or a step failed
      // causing us to kill all other running steps. Neither of these is an exceptional
      // situation.
      return new Result(1);
    } finally {
      process.destroy();
      process.waitFor();
    }

    Optional<String> stdoutText = getDataIfNotPrinted(stdOutToWriteTo, shouldPrintStdOut);
    Optional<String> stderrText = getDataIfNotPrinted(stdErrToWriteTo, shouldPrintStdErr);

    // Report the exit code of the Process.
    int exitCode = process.exitValue();

    // If the command has failed and we're not being explicitly quiet, ensure everything gets
    // printed.
    if (exitCode != 0 && !options.contains(Option.IS_SILENT)) {
      if (!shouldPrintStdOut && !stdoutText.get().isEmpty()) {
        LOG.verbose("Writing captured stdout text to stream: [%s]", stdoutText.get());
        stdOutStream.print(stdoutText.get());
      }
      if (!shouldPrintStdErr && !stderrText.get().isEmpty()) {
        LOG.verbose("Writing captured stderr text to stream: [%s]", stderrText.get());
        stdErrStream.print(stderrText.get());
      }
    }

    return new Result(exitCode, timedOut, stdoutText, stderrText);
  }

  private static Optional<String> getDataIfNotPrinted(
      PrintStream printStream,
      boolean shouldPrint) {
    if (!shouldPrint) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream) printStream;
      return Optional.of(capturingPrintStream.getContentsAsString(Charsets.US_ASCII));
    } else {
      return Optional.absent();
    }
  }

  /**
   * Values from the result of
   * {@link ProcessExecutor#execute(Process, Set, Optional, Optional)}.
   */
  public static class Result {

    private final int exitCode;
    private final boolean timedOut;
    private final Optional<String> stdout;
    private final Optional<String> stderr;

    public Result(
        int exitCode,
        boolean timedOut,
        Optional<String> stdout,
        Optional<String> stderr) {
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public Result(int exitCode, String stdout, String stderr) {
      this(exitCode, /* timedOut */ false, Optional.of(stdout), Optional.of(stderr));
    }

    public Result(int exitCode) {
      this(exitCode, /* timedOut */ false, Optional.<String>absent(), Optional.<String>absent());
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

  }

}
