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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.StreamHandler;

/**
 * Attach a buffered {@code PrintStream} and {@code StreamHandler} to the standards output ( {@code
 * System#setOut}, {@code System#setErr} ) and release once it's complete.
 *
 * @see #stdOut(Level)
 * @see #stdErr(Level)
 */
class StandardOutputRecorder {

  private static final String ENCODING = "UTF-8";
  private static final String NEWLINE = "\n";
  private static final JulLogFormatter FORMATTER = new JulLogFormatter();

  private final Level logLevel;
  private final PrintStream originalOutput;
  private final Consumer<PrintStream> outputRouter;
  private PrintStream outputStream;
  private ByteArrayOutputStream outputBuffer, logBuffer;
  private Handler logHandler;

  public static StandardOutputRecorder stdOut(Level logLevel) {
    try {
      return new StandardOutputRecorder(logLevel, System.out, System::setOut);
    } catch (Exception e) {
      throw new RuntimeException("failed to capture IO", e);
    }
  }

  public static StandardOutputRecorder stdErr(Level logLevel) {
    try {
      return new StandardOutputRecorder(logLevel, System.err, System::setErr);
    } catch (Exception e) {
      throw new RuntimeException("failed to capture IO", e);
    }
  }

  StandardOutputRecorder(
      Level logLevel, PrintStream originalOut, Consumer<PrintStream> outputRouter) {
    this.logLevel = logLevel;
    this.outputRouter = outputRouter;
    this.originalOutput = originalOut;
    this.outputBuffer = new ByteArrayOutputStream();
    this.logBuffer = new ByteArrayOutputStream();
  }

  private java.util.logging.Logger getLogger() {
    return LogManager.getLogManager().getLogger("");
  }

  /**
   * Create new PrintStream and Logger Handler to deviate output to buffers.
   *
   * @return Current instance
   * @throws IOException In case it is not possible to create a new PrintStream
   */
  public StandardOutputRecorder record() throws IOException {
    // Create an intermediate stdout/stderr to capture any debugging statements (usually in the
    // form of System.out.println) the developer is using to debug the test.
    outputStream = new PrintStream(outputBuffer, true, ENCODING);
    outputRouter.accept(outputStream);
    // Listen to any java.util.logging messages reported by the test and write them to logBuffer
    java.util.logging.Logger logger = getLogger();
    if (logger != null) {
      logger.setLevel(Level.FINE);
      logHandler = new StreamHandler(logBuffer, FORMATTER);
      logHandler.setLevel(logLevel);
      logger.addHandler(logHandler);
    }
    return this;
  }

  /**
   * Flush the recorded output to the buffers and restore original streams.
   *
   * @return Current instance
   */
  public StandardOutputRecorder complete() {
    // Restore the original stdout/stderr.
    outputRouter.accept(originalOutput);
    // Flush any debug logs and remove the handlers.
    if (logHandler != null) {
      logHandler.flush();
      java.util.logging.Logger logger = getLogger();
      if (logger != null) {
        logger.removeHandler(logHandler);
      }
      logHandler = null;
    }
    // Get the stdout/stderr written during the test as strings.
    if (outputStream != null) {
      outputStream.flush();
      outputStream = null;
    }
    return this;
  }

  /**
   * @param appendLog If the required string should contain the recorded logs or just the standard
   *     output.
   * @param logHeader Header separating std output from the logger output.
   * @return std output
   */
  public String toString(boolean appendLog, String logHeader) {
    try {
      String log = logBuffer.toString(ENCODING);
      String output = outputBuffer.toString(ENCODING);
      if (appendLog && !log.isEmpty()) {
        return new StringJoiner("").add(output).add(NEWLINE).add(logHeader).add(log).toString();
      }
      return output.isEmpty() ? null : output;
    } catch (IOException e) {
      throw new IllegalStateException("failed to encode output", e);
    }
  }
}
