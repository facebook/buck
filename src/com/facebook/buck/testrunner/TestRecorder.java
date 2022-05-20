/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.testrunner;

import java.io.IOException;
import java.util.logging.Level;

/** Record a test execution, saving the standard outputs and duration. */
class TestRecorder {

  private static final String DEBUG_LOGS_HEADER = "====DEBUG LOGS====\n\n";
  private static final String ERROR_LOGS_HEADER = "====ERROR LOGS====\n\n";

  private final StandardOutputRecorder stdOut;
  private final StandardOutputRecorder stdErr;
  private long startTime;
  private long endTime;

  TestRecorder(JUnitOptions options) {
    this.stdOut = StandardOutputRecorder.stdOut(options.getStdOutLogLevel().orElse(Level.INFO));
    this.stdErr = StandardOutputRecorder.stdErr(options.getStdErrLogLevel().orElse(Level.WARNING));
  }

  /**
   * Initialize and start recording standard outputs.
   *
   * @return Current instance.
   */
  public TestRecorder record() {
    try {
      startTime = System.currentTimeMillis();
      stdOut.record();
      stdErr.record();
    } catch (IOException e) {
      throw new IllegalStateException("failed to record standard output", e);
    }
    return this;
  }

  public TestRecorder complete() {
    endTime = System.currentTimeMillis();
    stdOut.complete();
    stdErr.complete();
    return this;
  }

  public long getDuration() {
    return Math.max(endTime - startTime, -1);
  }

  /**
   * Format standard output and log captured during the recording time.
   *
   * @param appendLog to indicate the output should append log content as well.
   * @return recorded standard output.
   */
  public String standardOutputAsString(boolean appendLog) {
    return stdOut.toString(appendLog, DEBUG_LOGS_HEADER);
  }

  /**
   * Format standard error output and log captured during the recording time.
   *
   * @param appendLog to indicate the output should append log content as well.
   * @return recorded standard error output.
   */
  public String standardErrorAsString(boolean appendLog) {
    return stdErr.toString(appendLog, ERROR_LOGS_HEADER);
  }
}
