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

import com.facebook.buck.logd.client.LogDaemonClient;
import com.facebook.buck.logd.client.LogdClient;
import com.facebook.buck.util.BgProcessKiller;
import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Starts LogD when buck process runs and shuts down LogD when buck process ends. */
public class LogdProvider implements AutoCloseable {
  private static final Logger LOG = LogManager.getLogger();
  private static final int LOGD_PROCESS_TIMEOUT_MS = 500;
  private static final Path PATH_TO_LOGD_PEX =
      Paths.get(
          System.getProperty(
              "buck.path_to_logd_pex", "src/com/facebook/buck/logd/resources/logd.pex"));

  private ProcessBuilder processBuilder;
  private Process logdProcess;
  private LogDaemonClient logdClient;

  /**
   * Constructor for LogdProvider.
   *
   * @param isLogdEnabled determines whether LogD is used. If set to false, LogdProvider does
   *     nothing.
   * @throws IOException if fails to run the external logd process
   */
  public LogdProvider(boolean isLogdEnabled) throws IOException {
    if (isLogdEnabled) {
      this.processBuilder = new ProcessBuilder(PATH_TO_LOGD_PEX.toString());
      this.processBuilder.redirectErrorStream(true);
      start();
    }
  }

  /**
   * Runs external logd process and reads the port number that LogD server is listening on.
   *
   * @throws IOException if ProcessBuilder fails to run the external logd process
   */
  private void start() throws IOException {
    LOG.info("Running external logd process...");
    logdProcess = startProcess();

    try (BufferedReader bufferedReader =
        new BufferedReader(
            new InputStreamReader(logdProcess.getInputStream(), StandardCharsets.UTF_8))) {
      int logdServerPort = Integer.parseInt(bufferedReader.readLine());
      logdClient = createLogdClient(logdServerPort);
    } catch (Exception e) {
      throw new IOException("Failed to read port info from running external logd process", e);
    }
  }

  @VisibleForTesting
  Process startProcess() throws IOException {
    return BgProcessKiller.startProcess(processBuilder);
  }

  @VisibleForTesting
  LogDaemonClient createLogdClient(int port) {
    return new LogdClient(port);
  }

  @Override
  public void close() {
    if (getLogdClient().isPresent()) {
      LOG.info("Shutting down LogD...");
      logdClient.requestLogdServerShutdown();
      logdClient.shutdown();

      try {
        logdProcess.waitFor(LOGD_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        LOG.info("LogD process got interrupted...", e);
        Thread.currentThread().interrupt();
      } finally {
        logdProcess.destroy();
      }
    }
  }

  /**
   * Returns the logdClient connected to LogD at {@code logdServerPort}
   *
   * @return Returns the logdClient connected to LogD at {@code logdServerPort}
   */
  public Optional<LogDaemonClient> getLogdClient() {
    return Optional.ofNullable(logdClient);
  }
}
