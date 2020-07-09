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
}
