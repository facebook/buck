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

package com.facebook.buck.io.watchman;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanClientIntegrationTest {

  private static final long timeoutMillis = 5000L;
  private static final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths watchmanBaseDir = new TemporaryPaths();

  private Path watchmanSockFile;
  private ListeningProcessExecutor executor;
  private ListeningProcessExecutor.LaunchedProcess watchmanProcess;

  private void startWatchman() throws IOException, InterruptedException {
    Optional<Path> watchmanExe =
        new ExecutableFinder()
            .getOptionalExecutable(WatchmanFactory.WATCHMAN, ImmutableMap.copyOf(System.getenv()));

    if (!isSupportedPlatform() || !watchmanExe.isPresent()) {
      return;
    }

    Path watchmanCfgFile = watchmanBaseDir.getRoot().resolve("config.json");
    // default config
    Files.write(watchmanCfgFile, "{}".getBytes());

    Path watchmanLogFile = watchmanBaseDir.getRoot().resolve("log");
    Path watchmanPidFile = watchmanBaseDir.getRoot().resolve("pid");

    if (Platform.detect() == Platform.WINDOWS) {
      Random random = new Random(0);
      UUID uuid = new UUID(random.nextLong(), random.nextLong());
      watchmanSockFile = Paths.get("\\\\.\\pipe\\watchman-test-" + uuid);
    } else {
      watchmanSockFile = watchmanBaseDir.getRoot().resolve("sock");
    }

    Path watchmanStateFile = watchmanBaseDir.getRoot().resolve("state");

    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand(
                watchmanExe.get().toString(),
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
    executor = new ListeningProcessExecutor();

    watchmanProcess = executor.launchProcess(params, new SimpleProcessListener());

    waitForWatchman();
  }

  private void waitForWatchman() throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      try {
        Optional<WatchmanClient> optClient =
            WatchmanFactory.tryCreateWatchmanClient(
                watchmanSockFile, new TestConsole(), new DefaultClock());
        try {
          if (optClient.isPresent()) {
            optClient.get().queryWithTimeout(timeoutMillis, "get-pid");
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

  @Before
  public void setUp() throws IOException, InterruptedException {
    // watchman tests are currently very flaky on Linux
    Assume.assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.LINUX)));
    startWatchman();
    Assume.assumeTrue(watchmanProcess != null);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "watchman", tmp);
    workspace.setUp();
  }

  @After
  public void tearDown() {
    if (watchmanProcess != null && executor != null) {
      executor.destroyProcess(watchmanProcess, true);
    }
  }

  @Test
  public void testWatchmanGlob() throws InterruptedException, IOException {
    Optional<WatchmanClient> clientOpt =
        WatchmanFactory.tryCreateWatchmanClient(
            watchmanSockFile, new TestConsole(), new DefaultClock());
    Assert.assertTrue(clientOpt.isPresent());

    WatchmanClient client = clientOpt.get();
    Optional<? extends Map<String, ?>> versionResponse =
        client.queryWithTimeout(
            timeoutNanos,
            "version",
            ImmutableMap.of(
                "required",
                WatchmanFactory.REQUIRED_CAPABILITIES,
                "optional",
                WatchmanFactory.ALL_CAPABILITIES.keySet()));
    Assert.assertTrue(versionResponse.isPresent());

    Path rootPath = workspace.getDestPath();

    Optional<? extends Map<String, ?>> watch =
        client.queryWithTimeout(timeoutNanos, "watch-project", rootPath.toString());

    Assert.assertNotNull(watch.isPresent());

    Map<String, ?> map = watch.get();
    String watchRoot = (String) map.get("watch");

    Optional<? extends Map<String, ?>> queryResponse =
        client.queryWithTimeout(
            timeoutNanos,
            "query",
            watchRoot,
            ImmutableMap.<String, Object>of(
                "glob", ImmutableList.of("**/X"),
                "fields", ImmutableList.of("name")));

    Assert.assertTrue(queryResponse.isPresent());

    Set<?> actualFileSet = ImmutableSet.copyOf((List<?>) queryResponse.get().get("files"));
    Set<?> expectedFileSet = ImmutableSet.of("X", "f1/X", "f2/X");

    Assert.assertEquals(expectedFileSet, actualFileSet);
    client.close();
  }

  private static boolean isSupportedPlatform() {
    switch (Platform.detect()) {
      case LINUX:
      case MACOS:
      case WINDOWS:
        return true;
        // $CASES-OMITTED$
      default:
        return false;
    }
  }
}
