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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessListeners;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class WatchmanTestDaemon implements Closeable {
  public class StartingWatchmanTimedOutException extends IOException {}

  private static final Logger LOG = Logger.get(WatchmanTestDaemon.class);

  private static final long timeoutMillis = 5000L;
  private static final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

  private final ListeningProcessExecutor executor;
  @Nullable private ListeningProcessExecutor.LaunchedProcess watchmanProcess;
  private final Path watchmanSockFile;
  private final Path watchmanLogFile;

  private WatchmanTestDaemon(
      ListeningProcessExecutor executor,
      @Nullable ListeningProcessExecutor.LaunchedProcess watchmanProcess,
      Path watchmanSockFile,
      Path watchmanLogFile) {
    this.executor = executor;
    this.watchmanProcess = watchmanProcess;
    this.watchmanSockFile = watchmanSockFile;
    this.watchmanLogFile = watchmanLogFile;
  }

  public static WatchmanTestDaemon start(Path watchmanBaseDir, ListeningProcessExecutor executor)
      throws IOException, InterruptedException, WatchmanNotFoundException {
    Path watchmanExe;
    try {
      watchmanExe =
          new ExecutableFinder()
              .getExecutable(WatchmanFactory.WATCHMAN, EnvVariablesProvider.getSystemEnv());
    } catch (HumanReadableException e) {
      WatchmanNotFoundException exception = new WatchmanNotFoundException();
      exception.initCause(e);
      throw exception;
    }

    Path watchmanCfgFile = watchmanBaseDir.resolve("config.json");
    // default config
    Files.write(watchmanCfgFile, "{}".getBytes());

    Path watchmanLogFile = watchmanBaseDir.resolve("log");
    Path watchmanPidFile = watchmanBaseDir.resolve("pid");

    Path watchmanSockFile;
    if (Platform.detect() == Platform.WINDOWS) {
      Random random = new Random(0);
      UUID uuid = new UUID(random.nextLong(), random.nextLong());
      watchmanSockFile = Paths.get("\\\\.\\pipe\\watchman-test-" + uuid);
    } else {
      watchmanSockFile = watchmanBaseDir.resolve("sock");
    }

    Path watchmanStateFile = watchmanBaseDir.resolve("state");

    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand(
                watchmanExe.toString(),
                "--foreground",
                "--log-level=2",
                "--sockname=" + watchmanSockFile,
                "--logfile=" + watchmanLogFile,
                "--statefile=" + watchmanStateFile,
                "--pidfile=" + watchmanPidFile)
            .setEnvironment(
                ImmutableMap.of(
                    "WATCHMAN_CONFIG_FILE", watchmanCfgFile.toString(),
                    "TMP", watchmanBaseDir.toString()))
            .build();

    WatchmanTestDaemon daemon =
        new WatchmanTestDaemon(
            executor,
            executor.launchProcess(params, new ProcessListeners.CapturingListener()),
            watchmanSockFile,
            watchmanLogFile);
    try {
      daemon.waitUntilReady();
      return daemon;
    } catch (Exception e) {
      daemon.close();
      throw e;
    }
  }

  private void waitUntilReady() throws InterruptedException, StartingWatchmanTimedOutException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (isWatchmanReady()) {
        return;
      }
      Thread.sleep(100L);
    }
    throw new StartingWatchmanTimedOutException();
  }

  private boolean isWatchmanReady() throws InterruptedException {
    try {
      try (WatchmanClient client =
          WatchmanFactory.createWatchmanClient(
              watchmanSockFile, new TestConsole(), new DefaultClock())) {
        Optional<?> response = client.queryWithTimeout(timeoutNanos, "get-pid");
        return response.isPresent();
      }
    } catch (IOException e) {
      LOG.warn(e, "Watchman is not ready");
      return false;
    }
  }

  public Path getTransportPath() {
    return watchmanSockFile;
  }

  @Override
  public void close() throws IOException {
    if (watchmanProcess == null) {
      return;
    }
    try {
      stopWatchmanProcess();
      watchmanProcess = null;
    } finally {
      dumpWatchmanLogs();
    }
  }

  private void stopWatchmanProcess() throws IOException {
    try {
      executor.destroyProcess(watchmanProcess, true);
      executor.waitForProcess(watchmanProcess);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void dumpWatchmanLogs() {
    LogConfig.flushLogs();
    PrintStream output = System.err;
    List<String> lines;
    try {
      lines = Files.readAllLines(watchmanLogFile);
    } catch (IOException e) {
      LOG.warn(e, "Could not read Watchman's log file");
      return;
    }
    output.printf("Watchman logs (%s):\n", watchmanLogFile);
    printIndentedLines(output, lines);
  }

  private static void printIndentedLines(PrintStream output, List<String> lines) {
    for (String line : lines) {
      output.print("    ");
      output.println(line);
    }
  }
}
