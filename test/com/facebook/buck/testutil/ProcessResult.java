/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static org.junit.Assert.fail;

import com.facebook.buck.util.ExitCode;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

public class ProcessResult {
  private final ExitCode exitCode;
  private final String stdout;
  private final String stderr;

  public ProcessResult(ExitCode exitCode, String stdout, String stderr) {
    this.exitCode = exitCode;
    this.stdout = Preconditions.checkNotNull(stdout);
    this.stderr = Preconditions.checkNotNull(stderr);
  }

  /**
   * Returns the exit code from the process.
   *
   * <p>Currently, in practice, any time a client might want to use it, it is more appropriate to
   * use {@link #assertSuccess()} or {@link #assertFailure()} instead.
   */
  public ExitCode getExitCode() {
    return exitCode;
  }

  public String getStdout() {
    return stdout;
  }

  public String getStderr() {
    return stderr;
  }

  public ProcessResult assertSuccess() {
    return assertExitCode(null, ExitCode.SUCCESS);
  }

  public ProcessResult assertSuccess(String message) {
    return assertExitCode(message, ExitCode.SUCCESS);
  }

  public ProcessResult assertFailure() {
    return assertExitCode(null, ExitCode.BUILD_ERROR);
  }

  public ProcessResult assertTestFailure() {
    return assertExitCode(null, ExitCode.TEST_ERROR);
  }

  public ProcessResult assertTestFailure(String message) {
    return assertExitCode(message, ExitCode.TEST_ERROR);
  }

  public ProcessResult assertFailure(String message) {
    return assertExitCode(message, ExitCode.BUILD_ERROR);
  }

  public ProcessResult assertExitCode(@Nullable String message, ExitCode exitCode) {
    if (exitCode == getExitCode()) {
      return this;
    }

    String failureMessage =
        String.format(
            "Expected exit code %d but was %d.", exitCode.getCode(), getExitCode().getCode());
    if (message != null) {
      failureMessage = message + " " + failureMessage;
    }

    System.err.println("=== " + failureMessage + " ===");
    System.err.println("=== STDERR ===");
    System.err.println(getStderr());
    System.err.println("=== STDOUT ===");
    System.err.println(getStdout());
    fail(failureMessage);
    return this;
  }

  public ProcessResult assertSpecialExitCode(String message, ExitCode exitCode) {
    return assertExitCode(message, exitCode);
  }
}
