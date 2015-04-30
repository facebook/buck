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

/**
 * An indication of how verbose Buck should be. Enum values are in order from least to most verbose.
 */
public enum Verbosity {
  // TODO(mbolin): Consider introducing more Verbosity levels that affect the Java Logger setting.

  /** Do not print anything to the console. */
  SILENT,

  /**
   * Prints out the bare minimum required information, such as errors from build steps.
   */
  STANDARD_INFORMATION,

  /**
   * Print extra output from generated binaries and tests being run, but nothing else.
   */
  BINARY_OUTPUTS,

  /** Print the command being executed, but do not print its output. */
  COMMANDS,

  /** Commands plus the output from some select commands of intereset. */
  COMMANDS_AND_SPECIAL_OUTPUT,

  /** Print the command being executed followed by its output. */
  COMMANDS_AND_OUTPUT,

  /**
   * If the command being executed has its own {@code --verbose} option or equivalent, it should be
   * used.
   */
  ALL,
  ;

  public boolean shouldPrintStandardInformation() {
    return this.ordinal() >= STANDARD_INFORMATION.ordinal();
  }

  public boolean shouldPrintBinaryRunInformation() {
    return this.ordinal() >= BINARY_OUTPUTS.ordinal();
  }

  public boolean shouldPrintCommand() {
    return this.ordinal() >= COMMANDS.ordinal();
  }

  public boolean shouldPrintSelectCommandOutput() {
    return this.ordinal() >= COMMANDS_AND_SPECIAL_OUTPUT.ordinal();
  }

  public boolean shouldPrintOutput() {
    return this.ordinal() >= COMMANDS_AND_OUTPUT.ordinal();
  }

  /**
   * @return {@code true} if the command being executed should use its own {@code --verbose}
   *     argument (or equivalent) for this Verbosity level
   */
  public boolean shouldUseVerbosityFlagIfAvailable() {
    return this == ALL;
  }
}
