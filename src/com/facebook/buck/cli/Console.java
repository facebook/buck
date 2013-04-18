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

package com.facebook.buck.cli;

import com.facebook.buck.util.Ansi;
import com.google.common.base.Preconditions;

import java.io.PrintStream;

class Console {

  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final Ansi ansi;

  Console(PrintStream stdOut, PrintStream stdErr, Ansi ansi) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.ansi = Preconditions.checkNotNull(ansi);
  }

  Ansi getAnsi() {
    return ansi;
  }

  PrintStream getStdOut() {
    return stdOut;
  }

  PrintStream getStdErr() {
    return stdErr;
  }

  /**
   * @param successMessage single line of text without a trailing newline. If stdErr is attached to
   *     a terminal, then this will append an ANSI reset escape sequence followed by a newline.
   */
  void printSuccess(String successMessage) {
    Preconditions.checkArgument(!successMessage.endsWith("\n"),
        "Trailing newline will be added by this method");
    ansi.printHighlightedSuccessText(stdErr, successMessage);
    stdErr.print('\n');
  }

  void printFailureWithStacktrace(Throwable t) {
    t.printStackTrace(stdErr);
    printFailure("Unexpected internal error (this is probably a buck bug).");
  }

  void printFailureWithoutStacktrace(Throwable t) {
    printFailure(t.getMessage());
  }

  void printFailure(String failureMessage) {
    ansi.printlnHighlightedFailureText(stdErr, String.format("BUILD FAILED: %s", failureMessage));
  }
}
