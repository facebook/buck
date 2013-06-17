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

  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final Ansi ansi;

  public Console(PrintStream stdOut, PrintStream stdErr, Ansi ansi) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.ansi = Preconditions.checkNotNull(ansi);
  }

  public Ansi getAnsi() {
    return ansi;
  }

  public PrintStream getStdOut() {
    return stdOut;
  }

  public PrintStream getStdErr() {
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

  public void printFailureWithStacktrace(Throwable t) {
    t.printStackTrace(stdErr);
    printFailure("Unexpected internal error (this is probably a buck bug).");
  }

  /**
   * Prints an error message to stderr that will be highlighted in red if stderr is a tty.
   */
  public void printErrorText(String message) {
    stdErr.print(ansi.asErrorText(message));
  }

  public void printFailureWithoutStacktrace(Throwable t) {
    printFailure(Throwables.getRootCause(t).getMessage());
  }

  public void printFailure(String failureMessage) {
    ansi.printlnHighlightedFailureText(stdErr, String.format("BUILD FAILED: %s", failureMessage));
  }
}
