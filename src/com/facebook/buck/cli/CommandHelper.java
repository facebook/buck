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

package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.util.Ansi;
import java.io.PrintStream;

/** Utility class with print methods */
public final class CommandHelper {

  private CommandHelper() {}

  /**
   * Prints short description of a given command into printStream.
   *
   * @param command CLI command
   * @param printStream print stream for output
   */
  public static void printShortDescription(Command command, PrintStream printStream) {
    printStream.println("Description: ");
    printStream.println("  " + command.getShortDescription());
    printStream.println();
  }

  /**
   * Prints a warning to terminal about --show-output being replaced by --show-outputs if the
   * warning is enabled in the buck config and the environment supports ANSI.
   */
  public static void maybePrintShowOutputWarning(
      CliConfig cliConfig, Ansi ansi, BuckEventBus buckEventBus) {
    if (ansi.isAnsiTerminal() && cliConfig.getEnableShowOutputWarning()) {
      buckEventBus.post(
          ConsoleEvent.warning(
              "--show-output is being deprecated. Use --show-outputs instead. Note that with "
                  + "--show-outputs, multiple files may be printed for each individual build "
                  + "target."));
    }
  }
}
