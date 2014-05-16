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

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;

import java.io.PrintStream;

import javax.annotation.Nullable;

/**
 * Executes a {@link Process} and blocks until it is finished.
 */
public class ProcessExecutor {

  private final PrintStream stdOutStream;
  private final PrintStream stdErrStream;
  private final Ansi ansi;

  /**
   * Creates a new {@link ProcessExecutor} with the specified parameters used for writing the output
   * of the process.
   */
  public ProcessExecutor(Console console) {
    Preconditions.checkNotNull(console);
    this.stdOutStream = console.getStdOut();
    this.stdErrStream = console.getStdErr();
    this.ansi = console.getAnsi();
  }

  /**
   * Convenience method for {@link #execute(Process, boolean, boolean, boolean)} with boolean values
   * set to {@code false}.
   */
  public Result execute(Process process) {
    return execute(process,
        /* shouldPrintStdOut */ false,
        /* shouldPrintStdErr */ false,
        /* isSilent */ false);
  }

  /**
   * Executes the specified process.
   * @param process The {@code Process} to execute.
   * @param shouldPrintStdOut If {@code true}, then the stdout of the process will be written
   *     directly to the stdout passed to the constructor of this executor. If {@code false}, then
   *     the stdout of the process will be made available via {@link Result#getStdout()}.
   * @param shouldPrintStdErr If {@code true}, then the stderr of the process will be written
   *     directly to the stderr passed to the constructor of this executor. If {@code false}, then
   *     the stderr of the process will be made available via {@link Result#getStderr()}.
   */
  public Result execute(
      Process process,
      boolean shouldPrintStdOut,
      boolean shouldPrintStdErr,
      boolean isSilent) {
    // Read stdout/stderr asynchronously while running a Process.
    // See http://stackoverflow.com/questions/882772/capturing-stdout-when-calling-runtime-exec
    @SuppressWarnings("resource")
    PrintStream stdOutToWriteTo = shouldPrintStdOut ?
        stdOutStream : new CapturingPrintStream();
    InputStreamConsumer stdOut = new InputStreamConsumer(
        process.getInputStream(),
        stdOutToWriteTo,
        ansi);

    @SuppressWarnings("resource")
    PrintStream stdErrToWriteTo = shouldPrintStdErr ?
        stdErrStream : new CapturingPrintStream();
    InputStreamConsumer stdErr = new InputStreamConsumer(
        process.getErrorStream(),
        stdErrToWriteTo,
        ansi);

    // Consume the streams so they do not deadlock.
    Thread stdOutConsumer = Threads.namedThread("ProcessExecutor (stdOut)", stdOut);
    stdOutConsumer.start();
    Thread stdErrConsumer = Threads.namedThread("ProcessExecutor (stdErr)", stdErr);
    stdErrConsumer.start();

    // Block until the Process completes.
    try {
      process.waitFor();
      stdOutConsumer.join();
      stdErrConsumer.join();
    } catch (InterruptedException e) {
      // Buck was killed while waiting for the consumers to finish. This means either the user
      // killed the process or a step failed causing us to kill all other running steps. Neither of
      // these is an exceptional situation.
      return new Result(1, /* stdout */ null, /* stderr */ null);
    } finally {
      process.destroy();
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        // Swallow the exception and continue/return.
      }
    }

    String stdoutText = getDataIfNotPrinted(stdOutToWriteTo, shouldPrintStdOut);
    String stderrText = getDataIfNotPrinted(stdErrToWriteTo, shouldPrintStdErr);

    // Report the exit code of the Process.
    int exitCode = process.exitValue();

    // If the command has failed and we're not being explicitly quiet, ensure everything gets
    // printed.
    if (exitCode != 0 && !isSilent) {
      if (!shouldPrintStdOut) {
        stdOutStream.print(stdoutText);
      }
      if (!shouldPrintStdErr) {
        stdErrStream.print(stderrText);
      }
    }

    return new Result(exitCode, stdoutText, stderrText);
  }

  @Nullable
  private static String getDataIfNotPrinted(PrintStream printStream, boolean shouldPrint) {
    if (!shouldPrint) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream) printStream;
      return capturingPrintStream.getContentsAsString(Charsets.US_ASCII);
    } else {
      return null;
    }
  }

  /**
   * Values from the result of {@link ProcessExecutor#execute(Process, boolean, boolean, boolean)}.
   */
  public static class Result {
    private final int exitCode;
    @Nullable private final String stdout;
    @Nullable private final String stderr;

    public Result(int exitCode, @Nullable String stdOut, @Nullable String stderr) {
      this.exitCode = exitCode;
      this.stdout = stdOut;
      this.stderr = stderr;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Nullable
    public String getStdout() {
      return stdout;
    }

    @Nullable
    public String getStderr() {
      return stderr;
    }
  }
}
