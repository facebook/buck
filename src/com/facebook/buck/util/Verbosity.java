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

package com.facebook.buck.util;

/**
 * An indication of how verbose Buck should be. Enum values are in order from least to most verbose.
 */
public enum Verbosity {
  // TODO(mbolin): Consider introducing more Verbosity levels that affect the Java Logger
  // setting.

  /** Do not print anything to the console. */
  SILENT(0),

  /** Prints out the bare minimum required information, such as errors from build steps. */
  STANDARD_INFORMATION(1),

  /** Print extra output from generated binaries and tests being run, but nothing else. */
  BINARY_OUTPUTS(2),

  /** Print the command being executed, but do not print its output. */
  COMMANDS(3),

  /** Commands plus the output from some select commands of interest. */
  COMMANDS_AND_SPECIAL_OUTPUT(4),

  /** Print the command being executed followed by its output. */
  COMMANDS_AND_OUTPUT(5),

  /**
   * If the command being executed has its own {@code --verbose} option or equivalent, it should be
   * used.
   */
  ALL(100),
  ;

  private final int level;

  Verbosity(int level) {
    this.level = level;
  }

  public boolean isSilent() {
    return level <= SILENT.level;
  }

  public boolean shouldPrintStandardInformation() {
    return level >= STANDARD_INFORMATION.level;
  }

  public boolean shouldPrintBinaryRunInformation() {
    return level >= BINARY_OUTPUTS.level;
  }

  public boolean shouldPrintCommand() {
    return level >= COMMANDS.level;
  }

  public boolean shouldPrintSelectCommandOutput() {
    return level >= COMMANDS_AND_SPECIAL_OUTPUT.level;
  }

  public boolean shouldPrintOutput() {
    return level >= COMMANDS_AND_OUTPUT.level;
  }

  /**
   * @return {@code true} if the command being executed should use its own {@code --verbose}
   *     argument (or equivalent) for this Verbosity level
   */
  public boolean shouldUseVerbosityFlagIfAvailable() {
    return this == ALL;
  }
}
