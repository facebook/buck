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

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import java.io.PrintStream;

public class Console {

  private final Verbosity verbosity;
  private final DirtyPrintStreamDecorator stdOut;
  private final DirtyPrintStreamDecorator stdErr;
  private final Ansi ansi;

  public Console(Verbosity verbosity,
      PrintStream stdOut,
      PrintStream stdErr,
      Ansi ansi) {
    this.verbosity = Preconditions.checkNotNull(verbosity);
    this.stdOut = new DirtyPrintStreamDecorator(Preconditions.checkNotNull(stdOut));
    this.stdErr = new DirtyPrintStreamDecorator(Preconditions.checkNotNull(stdErr));
    this.ansi = Preconditions.checkNotNull(ansi);
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }

  public Ansi getAnsi() {
    return ansi;
  }

  public DirtyPrintStreamDecorator getStdOut() {
    return stdOut;
  }

  public DirtyPrintStreamDecorator getStdErr() {
    return stdErr;
  }

  /**
   * @param successMessage single line of text without a trailing newline. If stdErr is attached to
   *     a terminal, then this will append an ANSI reset escape sequence followed by a newline.
   */
  public void printSuccess(String successMessage) {
    Preconditions.checkArgument(!successMessage.endsWith("\n"),
        "Trailing newline will be added by this method");
    ansi.printHighlightedSuccessText(stdErr, successMessage);
    stdErr.print('\n');
  }

  /**
   * Prints an error message to stderr that will be highlighted in red if stderr is a tty.
   */
  public void printErrorText(String message) {
    stdErr.println(ansi.asErrorText(message));
  }

  /**
   * Prints the root cause of the {@link Throwable} with its stacktrace as a build failure.
   * @see #printBuildFailure(String)
   */
  public void printBuildFailureWithStacktrace(Throwable t) {
    t.printStackTrace(stdErr);
    printBuildFailure("Unexpected internal error (if you are using buckd, you should restart it).");
  }

  /**
   * Prints the message of the root cause of the {@link Throwable} as a build failure.
   * @see #printBuildFailure(String)
   */
  public void printBuildFailureWithoutStacktrace(Throwable t) {
    printBuildFailure(Throwables.getRootCause(t).getMessage());
  }

  /**
   * Prints an error message prefixed with {@code BUILD FAILED} to stderr that will be highlighted
   * in red if stderr is a tty.
   */
  public void printBuildFailure(String failureMessage) {
    ansi.printlnHighlightedFailureText(stdErr, String.format("BUILD FAILED: %s", failureMessage));
  }
}
