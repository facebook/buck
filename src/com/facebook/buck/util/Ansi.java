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

import java.io.PrintStream;

/**
 * Encapsulates the specifics of writing to a particular kind of terminal.
 */
public final class Ansi {

  private static final String RESET = "\u001B[0m";

  private static final String BOLD = "\u001B[1m";

  private static final String BLACK = "\u001B[30m";
  private static final String GREY = "\u001B[30;1m";

  private static final String WHITE = "\u001B[37m";

  private static final String RED = "\u001B[31m";
  private static final String YELLOW = "\u001B[33m";
  private static final String GREEN = "\u001B[32m";

  private static final String ERROR_SEQUENCE = RED;
  private static final String WARNING_SEQUENCE = YELLOW;
  private static final String SUCCESS_SEQUENCE = GREEN;
  private static final String SUBTLE_SEQUENCE = GREY;

  private static final String BACKGROUND_RED = "\u001B[41m";
  private static final String BACKGROUND_YELLOW = "\u001B[43m";
  private static final String BACKGROUND_GREEN = "\u001B[42m";

  private static final String HIGHLIGHTED_ERROR_SEQUENCE = BOLD + BACKGROUND_RED + WHITE;
  private static final String HIGHLIGHTED_WARNING_SEQUENCE = BOLD + BACKGROUND_YELLOW + BLACK;
  private static final String HIGHLIGHTED_SUCCESS_SEQUENCE = BOLD + BACKGROUND_GREEN + BLACK;

  private static final String CURSOR_PREVIOUS_LINE = "\u001B[%dA";

  private static final String ERASE_IN_LINE = "\u001B[%dK";

  private static final String STOP_WRAPPING = "\u001B[?7l";
  private static final String RESUME_WRAPPING = "\u001B[?7h";

  private final boolean isAnsiTerminal;

  private static final Ansi noTtyAnsi = new Ansi(false /* isAnsiTerminal */);
  private static final Ansi forceTtyAnsi = new Ansi(true /* isAnsiTerminal */);

  /**
   * Construct an Ansi object and conditionally enable fancy escape sequences.
   */
  public Ansi(boolean isAnsiTerminal) {
    this.isAnsiTerminal = isAnsiTerminal;
  }

  public static Ansi withoutTty() {
    return noTtyAnsi;
  }

  public static Ansi forceTty() {
    return forceTtyAnsi;
  }

  public boolean isAnsiTerminal() {
    return isAnsiTerminal;
  }

  public String asErrorText(String text) {
    return wrapWithColor(ERROR_SEQUENCE, text);
  }

  public String asWarningText(String text) {
    return wrapWithColor(WARNING_SEQUENCE, text);
  }

  public String asSuccessText(String text) {
    return wrapWithColor(SUCCESS_SEQUENCE, text);
  }

  public String asSubtleText(String text) {
    return wrapWithColor(SUBTLE_SEQUENCE, text);
  }

  public String getHighlightedWarningSequence() {
    return isAnsiTerminal ? HIGHLIGHTED_ERROR_SEQUENCE : "";
  }

  public String getHighlightedResetSequence() {
    return isAnsiTerminal ? RESET : "";
  }

  public String asHighlightedFailureText(String text) {
    return wrapWithColor(HIGHLIGHTED_ERROR_SEQUENCE, text);
  }

  public String asHighlightedWarningText(String text) {
    return wrapWithColor(HIGHLIGHTED_WARNING_SEQUENCE, text);
  }

  public String asHighlightedSuccessText(String text) {
    return wrapWithColor(HIGHLIGHTED_SUCCESS_SEQUENCE, text);
  }

  public String asNoWrap(String text) {
    if (isAnsiTerminal) {
      return STOP_WRAPPING + text + RESUME_WRAPPING;
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

  public String asHighlightedStatusText(SeverityLevel level, String text) {
    switch (level) {
      case OK:
        return asHighlightedSuccessText(text);
      case WARNING:
        return asHighlightedWarningText(text);
      case ERROR:
        return asHighlightedFailureText(text);
      default:
        String message = String.format("Unexpected SeverityLevel; cannot highlight '%s'!", level);
        throw new IllegalArgumentException(message);
    }
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

  /**
   * Moves the cursor {@code y} lines up.
   */
  public String cursorPreviousLine(int y) {
    if (isAnsiTerminal) {
      return String.format(CURSOR_PREVIOUS_LINE, y);
    } else {
      return "";
    }
  }

  /**
   * Clears the line the cursor is currently on.
   */
  public String clearLine() {
    if (isAnsiTerminal) {
      return String.format(ERASE_IN_LINE, 2);
    } else {
      return "";
    }
  }

  public static enum SeverityLevel { OK, WARNING, ERROR }

  private String wrapWithColor(String color, String text) {
    if (!isAnsiTerminal) {
      return text;
    }
    // Skip tabs, because they don't like being prefixed with color.
    int firstNonTab = indexOfFirstNonTab(text);
    if (firstNonTab == -1) {
      return color + text + RESET;
    }
    return text.substring(0, firstNonTab) + color + text.substring(firstNonTab) + RESET;
  }

  /**
   * @return the index of the first character that's not a tab, or -1 if none is found.
   */
  private static int indexOfFirstNonTab(String s) {
    final int length = s.length();
    for (int i = 0; i < length; i++) {
      if (s.charAt(i) != '\t') {
        return i;
      }
    }
    return -1;
  }
}
