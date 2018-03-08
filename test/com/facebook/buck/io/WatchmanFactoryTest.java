/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.io;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.WatchmanFactory.InitialWatchmanClientFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.bser.BserSerializer;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WatchmanFactoryTest {

  private String root = Paths.get("/some/root").toAbsolutePath().toString();
  private ImmutableSet<Path> rootPaths = ImmutableSet.of(Paths.get(root));
  private String exe = Paths.get("/opt/bin/watchman").toAbsolutePath().toString();
  private FakeExecutableFinder finder = new FakeExecutableFinder(Paths.get(exe));
  private ImmutableMap<String, String> env = ImmutableMap.of();
  private static final ImmutableList<Object> VERSION_QUERY =
      ImmutableList.of(
          "version",
          ImmutableMap.of(
              "required", ImmutableSet.of("cmd-watch-project"),
              "optional",
                  ImmutableSet.of(
                      "term-dirname",
                      "cmd-watch-project",
                      "wildmatch",
                      "wildmatch_multislash",
                      "glob_generator",
                      "clock-sync-timeout")));

  private static WatchmanFactory createFakeWatchmanFactory(
      Path socketName,
      long queryElapsedTimeNanos,
      Map<? extends List<? extends Object>, ? extends Map<String, ? extends Object>> queryResults) {
    InitialWatchmanClientFactory factory =
        new InitialWatchmanClientFactory() {
          @Override
          public WatchmanClient tryCreateClientToFetchInitialWatchmanData(
              Path path, Console console, Clock clock) throws IOException {
            if (path.equals(socketName)) {
              return new FakeWatchmanClient(queryElapsedTimeNanos, queryResults);
            } else {
              throw new IOException(String.format("bad path (%s != %s", path, socketName));
            }
          }
        };
    return new WatchmanFactory(factory);
  }

  private static ByteBuffer bserSerialized(Object obj) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
    ByteBuffer result = new BserSerializer().serializeToBuffer(obj, buf);
    // Prepare the buffer for reading.
    result.flip();
    return result;
  }

  @Test
  public void shouldReturnEmptyWatchmanIfNotOnPath() throws InterruptedException {
    FakeExecutableFinder finder = new FakeExecutableFinder();
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(ImmutableMultimap.of());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void shouldReturnEmptyWatchmanIfVersionCheckFails() throws InterruptedException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofExit(1))
                .build(),
            clock);

    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void shouldReturnNullWatchmanIfExtendedVersionCheckMissing()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.7.9",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);

    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of("version", "3.7.9"),
                ImmutableList.of("watch", root),
                ImmutableMap.of("version", "3.7.9", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void shouldFailIfWatchProjectNotAvailable() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);

    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.of(
                        "term-dirname", true,
                        "cmd-watch-project", false,
                        "wildmatch", false,
                        "wildmatch_multislash", false,
                        "glob_generator", false),
                    "error",
                    "client required capabilty `cmd-watch-project` is not supported by this "
                        + "server")));
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void watchmanVersionTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(30)),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.of(
                        "term-dirname", true,
                        "cmd-watch-project", false,
                        "wildmatch", false,
                        "wildmatch_multislash", false,
                        "glob_generator", false)),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.8.0", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestConsole(),
            clock,
            Optional.of(TimeUnit.SECONDS.toMillis(5)));

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void watchmanWatchProjectTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            TimeUnit.SECONDS.toNanos(30),
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", false)
                        .put("wildmatch", false)
                        .put("wildmatch_multislash", false)
                        .put("glob_generator", false)
                        .put("clock-sync-timeout", false)
                        .build()),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.8.0", "watch", root)));
    Watchman watchman =
        watchmanFactory.build(
            executor,
            rootPaths,
            env,
            finder,
            new TestConsole(),
            clock,
            Optional.of(TimeUnit.SECONDS.toMillis(5)));

    assertEquals(WatchmanFactory.NULL_WATCHMAN, watchman);
  }

  @Test
  public void capabilitiesDetectedForVersion38AndLater() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "3.8.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", true)
                        .put("wildmatch", true)
                        .put("wildmatch_multislash", true)
                        .put("glob_generator", false)
                        .put("clock-sync-timeout", false)
                        .build()),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.8.0", "watch", root),
                ImmutableList.of("clock", root, ImmutableMap.of()),
                ImmutableMap.of("version", "3.8.0", "clock", "c:0:0:1")));
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(
        ImmutableSet.of(
            WatchmanFactory.Capability.DIRNAME,
            WatchmanFactory.Capability.SUPPORTS_PROJECT_WATCH,
            WatchmanFactory.Capability.WILDMATCH_GLOB,
            WatchmanFactory.Capability.WILDMATCH_MULTISLASH),
        watchman.getCapabilities());

    assertEquals(ImmutableMap.of(root, "c:0:0:1"), watchman.getClockIds());
  }

  @Test
  public void capabilitiesDetectedForVersion47AndLater() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "4.7.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "4.7.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", true)
                        .put("wildmatch", true)
                        .put("wildmatch_multislash", true)
                        .put("glob_generator", true)
                        .put("clock-sync-timeout", true)
                        .build()),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "4.7.0", "watch", root),
                ImmutableList.of("clock", root, ImmutableMap.of("sync_timeout", 100)),
                ImmutableMap.of("version", "4.7.0", "clock", "c:0:0:1")));
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(
        ImmutableSet.of(
            WatchmanFactory.Capability.DIRNAME,
            WatchmanFactory.Capability.SUPPORTS_PROJECT_WATCH,
            WatchmanFactory.Capability.WILDMATCH_GLOB,
            WatchmanFactory.Capability.WILDMATCH_MULTISLASH,
            WatchmanFactory.Capability.GLOB_GENERATOR,
            WatchmanFactory.Capability.CLOCK_SYNC_TIMEOUT),
        watchman.getCapabilities());

    assertEquals(ImmutableMap.of(root, "c:0:0:1"), watchman.getClockIds());
  }

  @Test
  public void emptyClockQueryShouldReturnNullClock() throws InterruptedException, IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
                .putAll(
                    ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                    FakeListeningProcessState.ofStdoutBytes(
                        bserSerialized(
                            ImmutableMap.of(
                                "version", "4.7.0",
                                "sockname", "/path/to/sock"))),
                    FakeListeningProcessState.ofExit(0))
                .build(),
            clock);
    WatchmanFactory watchmanFactory =
        createFakeWatchmanFactory(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                VERSION_QUERY,
                ImmutableMap.of(
                    "version",
                    "4.7.0",
                    "capabilities",
                    ImmutableMap.<String, Boolean>builder()
                        .put("term-dirname", true)
                        .put("cmd-watch-project", true)
                        .put("wildmatch", true)
                        .put("wildmatch_multislash", true)
                        .put("glob_generator", true)
                        .put("clock-sync-timeout", true)
                        .build()),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "4.7.0", "watch", root),
                ImmutableList.of("clock", root, ImmutableMap.of("sync_timeout", 100)),
                ImmutableMap.of()));
    Watchman watchman =
        watchmanFactory.build(
            executor, rootPaths, env, finder, new TestConsole(), clock, Optional.empty());

    assertEquals(ImmutableMap.of(), watchman.getClockIds());
  }
}
