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

package com.facebook.buck.util.console;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.logging.Level;

/** Utility methods to work with Console/ConsoleEvents */
public class ConsoleUtils {

  private static final Logger LOG = Logger.get(ConsoleUtils.class);

  private ConsoleUtils() {}

  /** Formats a {@link ConsoleEvent} and adds it to {@code lines}. */
  public static ImmutableList<String> formatConsoleEvent(ConsoleEvent consoleEvent, Ansi ansi) {
    String message = consoleEvent.getMessage();
    if (message == null) {
      LOG.error("Formatting console event with null message");
      return ImmutableList.of();
    }

    String formattedLine = "";
    Level logEventLevel = consoleEvent.getLevel();
    if (consoleEvent.containsAnsiEscapeCodes() || logEventLevel.equals(Level.INFO)) {
      formattedLine = message;
    } else {
      if (logEventLevel.equals(Level.WARNING)) {
        formattedLine = ansi.asWarningText(message);
      } else if (logEventLevel.equals(Level.SEVERE)) {
        formattedLine = ansi.asHighlightedFailureText(message);
      }
    }

    if (formattedLine.isEmpty()) {
      return ImmutableList.of();
    }
    return ImmutableList.copyOf(Splitter.on(System.lineSeparator()).split(formattedLine));
  }
}
