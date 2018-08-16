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

import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.PrintStream;

public class Console {

  private static final Logger LOG = Logger.get(Console.class);

  private final Verbosity verbosity;
  private final DirtyPrintStreamDecorator stdOut;
  private final DirtyPrintStreamDecorator stdErr;
  private final Ansi ansi;

  private static final PrintStream NULL_PRINT_STREAM =
      new PrintStream(ByteStreams.nullOutputStream());
  private static final Console NULL_CONSOLE =
      new Console(Verbosity.SILENT, NULL_PRINT_STREAM, NULL_PRINT_STREAM, Ansi.withoutTty());

  /** Returns a {@link Console} that simply discards written bytes. */
  public static Console createNullConsole() {
    return NULL_CONSOLE;
  }

  public Console(Verbosity verbosity, PrintStream stdOut, PrintStream stdErr, Ansi ansi) {
    this.verbosity = verbosity;
    this.stdOut = new DirtyPrintStreamDecorator(stdOut);
    this.stdErr = new DirtyPrintStreamDecorator(stdErr);
    this.ansi = ansi;
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
    Preconditions.checkArgument(
        !successMessage.endsWith("\n"), "Trailing newline will be added by this method");
    LOG.debug("Build success: %s", successMessage);
    ansi.printHighlightedSuccessText(stdErr, successMessage);
    stdErr.print('\n');
  }

  /** Prints a formatted success message. {@see #printSuccess(String)} */
  public void printSuccess(String successMessage, Object... args) {
    printSuccess(String.format(successMessage, args));
  }

  /** Prints an error message to stderr that will be highlighted in red if stderr is a tty. */
  public void printErrorText(String message) {
    LOG.warn("Build error: %s", message);
    stdErr.println(ansi.asErrorText(message));
  }

  /** Prints a formatted error message. {@see #printErrorText(String)} */
  public void printErrorText(String message, Object... args) {
    printErrorText(String.format(message, args));
  }

  /**
   * Prints the message of the {@link Throwable} as a build failure.
   *
   * @see #printBuildFailure(String)
   */
  public void printBuildFailureWithoutStacktraceDontUnwrap(Throwable t) {
    LOG.warn(t, "Build error caused by exception");
    printBuildFailureInternal(t.getMessage());
  }

  /**
   * Prints an error message prefixed with {@code BUILD FAILED} to stderr that will be highlighted
   * in red if stderr is a tty.
   */
  public void printBuildFailure(String failureMessage) {
    LOG.warn("Build failure: %s", failureMessage);
    printBuildFailureInternal(failureMessage);
  }

  private void printBuildFailureInternal(String failureMessage) {
    ansi.printlnHighlightedFailureText(stdErr, String.format("BUILD FAILED: %s", failureMessage));
  }

  /** Prints error message to console in red, also logs stacktrace but does not display it */
  public void printFailure(String failureMessage) {
    LOG.warn("Command failure: %s", failureMessage);
    ansi.printlnHighlightedFailureText(stdErr, failureMessage);
  }

  /** Prints error message to console in red, also logs stacktrace but does not display it */
  public void printFailure(Throwable t, String failureMessage) {
    LOG.warn(t, failureMessage);
    ansi.printlnHighlightedFailureText(stdErr, failureMessage);
  }

  /** Prints error message to console in red, logs and displays stacktrace */
  public void printFailureWithStacktrace(Throwable t, String failureMessage) {
    printFailure(t, failureMessage);
    t.printStackTrace(stdErr);
  }
}
