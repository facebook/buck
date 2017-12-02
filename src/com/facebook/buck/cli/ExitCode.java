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

/**
 * ExitCode class defines Buck binary protocol, i.e. exit codes that Buck can report to a running
 * shell. In addition to exit codes defined below, we have to honor following conventions.
 *
 * <p>Buck bootstrapper must conform to the protocol, i.e. properly implement FATAL_BOOTSTRAP
 *
 * <p>Exit codes for interrupts do follow POSIX convention, i.e. 128 + SIGNAL_CODE, i.e SIGINT is
 * returned as 128 + 2 = 130
 *
 * <p>Exit codes 1-9 are reserved for non-fatal errors, like build errors
 *
 * <p>Exit codes 10-19 are reserved for fatal errors, like unexpected runtime exceptions
 *
 * <p>Binary protocol is open to extension but closed to modification.
 */
public enum ExitCode {
  // Success 0

  /** Buck command completed successfully */
  SUCCESS(0),

  // Non-fatal generic errors 1-9

  /** Build resulted in user-specific error */
  BUILD_ERROR(1),
  /** Buck daemon is busy processing another command */
  BUSY(2),
  /** User supplied incorrect command line options */
  COMMANDLINE_ERROR(3),
  /** There is nothing to build or evaluate for the command */
  NOTHING_TO_DO(4),

  // Fatal errors 10-19

  /** Generic Buck-internal non-recoverable error */
  FATAL_GENERIC(10),
  /** Non-recoverable error in Buck bootstrapper */
  FATAL_BOOTSTRAP(11),
  /** Non-recoverable OutOfMemory error */
  FATAL_OOM(12),

  // Other non-fatal errors 20 - 127

  /** Test run had user-specific test errors */
  TEST_ERROR(32),
  /** There was no tests found to run */
  TEST_NOTHING(64),

  // Signal processors 128+

  /** Command was interrupted (Ctrl + C) */
  SIGNAL_INTERRUPT(130);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  /** @return integer value of the exit code */
  public int getCode() {
    return code;
  }

  /** @return true if error is Buck internal non-recoverable failure */
  public boolean isFatal() {
    return code >= 10 && code < 20;
  }
}
