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

package com.facebook.buck.logd;

import com.facebook.buck.logd.client.LogdClient;
import com.facebook.buck.util.BgProcessKiller;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Starts LogD when buck process runs and shuts down LogD when buck process ends. */
public class LogdRunner implements AutoCloseable {
  private static final Logger LOG = LogManager.getLogger();
  private static final int LOGD_PROCESS_TIMEOUT_MS = 500;

  private ProcessBuilder processBuilder;
  private Process logdProcess;

  private final boolean isLogdEnabled;
  private int logdServerPort;

  private static final Path PATH_TO_LOGD_PEX =
      Paths.get(
          System.getProperty(
              "buck.path_to_logd_pex", "src/com/facebook/buck/logd/resources/logd.pex"));

  /**
   * Constructor for LogdRunner.
   *
   * @param isLogdEnabled determines whether LogD is used. If set to false, LogdRunner does nothing.
   * @throws IOException
   */
  public LogdRunner(boolean isLogdEnabled) throws IOException {
    this.isLogdEnabled = isLogdEnabled;
    if (isLogdEnabled) {
      this.processBuilder = new ProcessBuilder(PATH_TO_LOGD_PEX.toString());
      this.processBuilder.redirectErrorStream(true);
      start();
    }
  }

  /**
   * Runs logd.pex and reads the port number that LogD server is listening on.
   *
   * @throws IOException if ProcessBuilder fails to run logd.pex
   */
  private void start() throws IOException {
    LOG.info("Running logd.pex...");
    logdProcess = BgProcessKiller.startProcess(processBuilder);

    try (BufferedReader bufferedReader =
        new BufferedReader(
            new InputStreamReader(logdProcess.getInputStream(), StandardCharsets.UTF_8))) {
      logdServerPort = Integer.parseInt(bufferedReader.readLine());
    } catch (Exception e) {
      throw new IOException("Failed to read port info from running logd.pex", e);
    }
  }

  @Override
  public void close() throws Exception {
    if (isLogdEnabled) {
      LOG.info("Shutting down LogD...");
      LogdClient logdClient = new LogdClient(logdServerPort);
      logdClient.requestLogdServerShutdown();
      logdClient.shutdown();

      try {
        logdProcess.waitFor(LOGD_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } finally {
        logdProcess.destroy();
      }
    }
  }

  /**
   * Returns the port on which LogD Server is listening.
   *
   * @return the port number on which LogD Server is listening.
   */
  public int getLogdServerPort() {
    return logdServerPort;
  }
}
