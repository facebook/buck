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

import com.google.common.base.Strings;

import java.io.PrintStream;

public final class Ansi {

  private static final String RESET = "\u001B[0m";

  private static final String BOLD = "\u001B[1m";

  private static final String BLACK = "\u001B[30m";

  private static final String WHITE = "\u001B[37m";

  private static final String RED = "\u001B[31m";
  private static final String YELLOW = "\u001B[33m";
  private static final String GREEN = "\u001B[32m";

  private static final String ERROR_SEQUENCE = RED;
  private static final String WARNING_SEQUENCE = YELLOW;
  private static final String SUCCESS_SEQUENCE = GREEN;

  private static final String BACKGROUND_RED = "\u001B[41m";
  private static final String BACKGROUND_GREEN = "\u001B[42m";

  private static final String HIGHLIGHTED_WARNING_SEQUENCE = BOLD + BACKGROUND_RED + WHITE;

  private static final String HIGHLIGHTED_SUCCESS_SEQUENCE = BOLD + BACKGROUND_GREEN + BLACK;

  private final boolean isTerminalThatSupportsColor;

  private static final Ansi noTtyAnsi = new Ansi(false /* isTerminalThatSupportsColor */);

  public Ansi() {
    // BuildBot does not supply a value for $TERM when it runs, in which case we should not be
    // writing ANSI escape codes to stdout/stderr. Ideally, we would use isatty to determine whether
    // stdout and stderr are connected to a terminal, but Java does not afford us such an API. In
    // the future, we may just disable color output by default and support a .buckconfig file where
    // the user can opt-in to color output as a preference.
    this(Strings.nullToEmpty(System.getenv("TERM")).startsWith("xterm"));
  }

  private Ansi(boolean isTerminalThatSupportsColor) {
    this.isTerminalThatSupportsColor = isTerminalThatSupportsColor;
  }

  public static Ansi withoutTty() {
    return noTtyAnsi;
  }

  public String asErrorText(String text) {
    if (isTerminalThatSupportsColor) {
      return ERROR_SEQUENCE + text + RESET;
    } else {
      return text;
    }
  }

  public String asWarningText(String text) {
    if (isTerminalThatSupportsColor) {
      return WARNING_SEQUENCE + text + RESET;
    } else {
      return text;
    }
  }

  public String asSuccessText(String text) {
    if (isTerminalThatSupportsColor) {
      return SUCCESS_SEQUENCE + text + RESET;
    } else {
      return text;
    }
  }

  public String getHighlightedWarningSequence() {
    return isTerminalThatSupportsColor ? HIGHLIGHTED_WARNING_SEQUENCE : "";
  }

  public String getHighlightedResetSequence() {
    return isTerminalThatSupportsColor ? RESET : "";
  }

  public String asHighlightedFailureText(String text) {
    if (isTerminalThatSupportsColor) {
      return HIGHLIGHTED_WARNING_SEQUENCE + text + RESET;
    } else {
      return text;
    }
  }

  public String asHighlightedSuccessText(String text) {
    if (isTerminalThatSupportsColor) {
      return HIGHLIGHTED_SUCCESS_SEQUENCE + text + RESET;
    } else {
      return text;
    }
  }

  public void printHighlightedFailureText(PrintStream stream, String text) {
    stream.print(asHighlightedFailureText(text));
  }

  public void printHighlightedSuccessText(PrintStream stream, String text) {
    stream.print(asHighlightedSuccessText(text));
  }

  public void printlnHighlightedSuccessText(PrintStream stream, String text) {
    stream.println(asHighlightedSuccessText(text));
  }

  public void printlnHighlightedFailureText(PrintStream stream, String text) {
    stream.println(asHighlightedFailureText(text));
  }

  public String asHighlightedStatusText(boolean isSuccess, String text) {
    return isSuccess ? asHighlightedSuccessText(text) : asHighlightedFailureText(text);
  }

  public void printHighlightedStatusText(boolean isSuccess, PrintStream stream, String text) {
    if (isSuccess) {
      printHighlightedSuccessText(stream, text);
    } else {
      printHighlightedFailureText(stream, text);
    }
  }

  public void printlnHighlightedStatusText(boolean isSuccess, PrintStream stream, String text) {
    if (isSuccess) {
      printlnHighlightedSuccessText(stream, text);
    } else {
      printlnHighlightedFailureText(stream, text);
    }
  }
}
