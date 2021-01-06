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

package com.facebook.buck.tools.consistency;

import java.io.PrintStream;

/** A simple class that prints formatted additions and removals to an output stream */
public class DiffPrinter {

  private final String greenColor;
  private final String redColor;
  private final String resetColors;
  private final String boldColor;

  private final PrintStream outStream;

  /**
   * Creates an instance of {@link DiffPrinter}
   *
   * @param outStream The stream to write to
   * @param showColor Whether or not colors should be added
   */
  public DiffPrinter(PrintStream outStream, boolean showColor) {
    this.outStream = outStream;
    if (showColor) {
      greenColor = "\u001B[32m";
      redColor = "\u001B[31m";
      resetColors = "\u001B[0m";
      boldColor = "\033[1m";
    } else {
      greenColor = "";
      redColor = "";
      resetColors = "";
      boldColor = "";
    }
  }

  /**
   * Prints out a '+ %s' line, where %s is {@code change} This will potentially be colored.
   *
   * @param change The text to print out after '+ '
   */
  public void printAdd(String change) {
    outStream.print(greenColor);
    outStream.print("+ ");
    outStream.print(change);
    outStream.println(resetColors);
  }

  /**
   * Prints out a '- %s' line, where %s is {@code change}. This will potentially be colored.
   *
   * @param change The text to print out after '- '
   */
  public void printRemove(String change) {
    outStream.print(redColor);
    outStream.print("- ");
    outStream.print(change);
    outStream.println(resetColors);
  }

  /**
   * Prints out two pieces of text to a line and potentially formats them with bolding
   *
   * @param bolded This piece of text is printed first, and potentially will be bolded
   * @param nonBold This piece of text is printed immediately after the first and will not have
   *     bolding
   */
  public void printHeader(String bolded, String nonBold) {
    outStream.print(boldColor);
    outStream.print(bolded);
    outStream.print(resetColors);
    outStream.println(nonBold);
  }
}
