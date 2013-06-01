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
        /* shouldRecordStdOut */ false,
        /* shouldPrintStdErr */ false,
        /* isSilent */ false);
  }

  /**
   * Executes the specified process.
   * @param process The {@code Process} to execute.
   * @param shouldRecordStdOut If {@code true}, then {@link Result#getStdOut()} will be non-null in
   *     the returned value because it was recorded.
   * @param shouldPrintStdErr If {@code true}, then the stderr of the process will be written
   *     directly to the stderr passed to the constructor of this executor.
   */
  public Result execute(
      Process process,
      boolean shouldRecordStdOut,
      boolean shouldPrintStdErr,
      boolean isSilent) {
    // Read stdout/stderr asynchronously while running a Process.
    // See http://stackoverflow.com/questions/882772/capturing-stdout-when-calling-runtime-exec
    @SuppressWarnings("resource")
    PrintStream stdOutToWriteTo = shouldRecordStdOut ?
        new CapturingPrintStream() : stdOutStream;
    InputStreamConsumer stdOut = new InputStreamConsumer(
        process.getInputStream(),
        stdOutToWriteTo,
        shouldRecordStdOut,
        ansi);

    @SuppressWarnings("resource")
    PrintStream stdErrToWriteTo = shouldPrintStdErr ?
        stdErrStream : new CapturingPrintStream();
    InputStreamConsumer stdErr = new InputStreamConsumer(
        process.getErrorStream(),
        stdErrToWriteTo,
        /* shouldRedirectInputStreamToPrintStream */ true,
        ansi);

    // Consume the streams so they do not deadlock.
    Thread stdOutConsumer = new Thread(stdOut);
    stdOutConsumer.start();
    Thread stdErrConsumer = new Thread(stdErr);
    stdErrConsumer.start();

    // Block until the Process completes.
    try {
      process.waitFor();
      stdOutConsumer.join();
      stdErrConsumer.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    // If stdout was captured, then wait until its InputStreamConsumer has finished and get the
    // contents of the stdout PrintStream as a string.
    String stdOutText;
    if (shouldRecordStdOut) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream)stdOutToWriteTo;
      stdOutText = capturingPrintStream.getContentsAsString(Charsets.US_ASCII);
    } else {
      stdOutText = null;
    }

    // Report the exit code of the Process.
    int exitCode = process.exitValue();

    // If the command has failed and we're not being explicitly quiet, ensure stderr gets printed.
    if (exitCode != 0 && !shouldPrintStdErr && !isSilent) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream) stdErrToWriteTo;
      stdErrStream.print(capturingPrintStream.getContentsAsString(Charsets.US_ASCII));
    }

    return new Result(exitCode, stdOutText);
  }

  /**
   * Values from the result of {@link ProcessExecutor#execute(Process, boolean, boolean, boolean)}.
   */
  public static class Result {
    private final int exitCode;
    @Nullable private final String stdOut;

    public Result(int exitCode, @Nullable String stdOut) {
      this.exitCode = exitCode;
      this.stdOut = stdOut;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Nullable
    public String getStdOut() {
      return stdOut;
    }
  }
}
