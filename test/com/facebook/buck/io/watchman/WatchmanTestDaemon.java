/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.io.watchman;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.ExecutableFinder;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class WatchmanTestDaemon implements Closeable {
  private static final long timeoutMillis = 5000L;
  private static final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

  private final ListeningProcessExecutor executor;
  @Nullable private ListeningProcessExecutor.LaunchedProcess watchmanProcess;
  private final Path watchmanSockFile;

  private WatchmanTestDaemon(
      ListeningProcessExecutor executor,
      @Nullable ListeningProcessExecutor.LaunchedProcess watchmanProcess,
      Path watchmanSockFile) {
    this.executor = executor;
    this.watchmanProcess = watchmanProcess;
    this.watchmanSockFile = watchmanSockFile;
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
            watchmanSockFile);
    try {
      daemon.waitUntilReady();
      return daemon;
    } catch (Exception e) {
      daemon.close();
      throw e;
    }
  }

  private void waitUntilReady() throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      try {
        Optional<WatchmanClient> optClient =
            WatchmanFactory.tryCreateWatchmanClient(
                watchmanSockFile, new TestConsole(), new DefaultClock());
        try {
          if (optClient.isPresent()) {
            optClient.get().queryWithTimeout(timeoutNanos, "get-pid");
            break;
          }
        } finally {
          if (optClient.isPresent()) {
            optClient.get().close();
          }
        }
      } catch (IOException e) {
        Thread.sleep(100L);
      }
    }
  }

  public Path getTransportPath() {
    return watchmanSockFile;
  }

  @Override
  public void close() {
    if (watchmanProcess != null) {
      executor.destroyProcess(watchmanProcess, true);
      watchmanProcess = null;
    }
  }
}
