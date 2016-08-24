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

import com.facebook.buck.bser.BserSerializer;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

public class WatchmanTest {

  private String root = Paths.get("/some/root").toAbsolutePath().toString();
  private String exe = Paths.get("/opt/bin/watchman").toAbsolutePath().toString();
  private FakeExecutableFinder finder = new FakeExecutableFinder(Paths.get(exe));
  private ImmutableMap<String, String> env = ImmutableMap.of();
  private static final Function<Path, Optional<WatchmanClient>> NULL_WATCHMAN_CONNECTOR =
      new Function<Path, Optional<WatchmanClient>>() {
        @Override
        public Optional<WatchmanClient> apply(Path path) {
          return Optional.absent();
        }
      };

  private static Function<Path, Optional<WatchmanClient>> fakeWatchmanConnector(
      final Path socketName,
      final long queryElapsedTimeNanos,
      final Map<? extends List<? extends Object>, ? extends Map<String, ? extends Object>>
        queryResults) {
    return new Function<Path, Optional<WatchmanClient>>() {
      @Override
      public Optional<WatchmanClient> apply(Path path) {
        if (!path.equals(socketName)) {
          System.err.format("bad path (%s != %s", path, socketName);
          return Optional.absent();
        }
        return Optional.<WatchmanClient>of(
            new FakeWatchmanClient(queryElapsedTimeNanos, queryResults));
      }
    };
  }

  private static ByteBuffer bserSerialized(Object obj) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
    ByteBuffer result = new BserSerializer().serializeToBuffer(obj, buf);
    // Prepare the buffer for reading.
    result.flip();
    return result;
  }

  @Test
  public void shouldReturnEmptyWatchmanIfVersionCheckFails() throws
      InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                FakeListeningProcessState.ofExit(1))
            .build(),
        clock);

    Watchman watchman = Watchman.build(
        executor,
        NULL_WATCHMAN_CONNECTOR,
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }

  @Test
  public void shouldNotUseWatchProjectIfNotAvailable()
      throws InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                FakeListeningProcessState.ofStdoutBytes(
                    bserSerialized(
                        ImmutableMap.of(
                            "version", "3.0.0",
                            "sockname", "/path/to/sock"))),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);

    Watchman watchman = Watchman.build(
        executor,
        fakeWatchmanConnector(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                ImmutableList.of("watch", root),
                ImmutableMap.of("version", "3.0.0", "watch", root))),
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals("3.0.0", watchman.getVersion().get());
    assertEquals(root, watchman.getWatchRoot().get());
    assertEquals(Optional.absent(), watchman.getProjectPrefix());
  }

  @Test
  public void successfulExecutionPopulatesAWatchmanInstance()
      throws InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                FakeListeningProcessState.ofStdoutBytes(
                    bserSerialized(
                        ImmutableMap.of(
                            "version", "3.4.0",
                            "sockname", "/path/to/sock"))),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor,
        fakeWatchmanConnector(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.4.0", "watch", root))),
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals("3.4.0", watchman.getVersion().get());
    assertEquals(root, watchman.getWatchRoot().get());
    assertEquals(Optional.absent(), watchman.getProjectPrefix());
  }

  @Test
  public void watchmanVersionTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(30)),
                FakeListeningProcessState.ofStdoutBytes(
                    bserSerialized(
                        ImmutableMap.of(
                            "version", "3.4.0",
                            "sockname", "/path/to/sock"))),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor,
        fakeWatchmanConnector(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.4.0", "watch", root))),
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }

  @Test
  public void watchmanWatchProjectTakingThirtySecondsReturnsEmpty()
      throws InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "--output-encoding=bser", "get-sockname"),
                FakeListeningProcessState.ofStdoutBytes(
                    bserSerialized(
                        ImmutableMap.of(
                            "version", "3.4.0",
                            "sockname", "/path/to/sock"))))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor,
        fakeWatchmanConnector(
            Paths.get("/path/to/sock"),
            TimeUnit.SECONDS.toNanos(30),
            ImmutableMap.of(
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.4.0", "watch", root))),
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }

  @Test
  public void capabilitiesDetectedForVersion38AndLater()
      throws InterruptedException, IOException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
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
    Watchman watchman = Watchman.build(
        executor,
        fakeWatchmanConnector(
            Paths.get("/path/to/sock"),
            0,
            ImmutableMap.of(
                ImmutableList.of(
                    "version",
                    ImmutableMap.of(
                        "optional",
                        ImmutableSet.of(
                            "term-dirname",
                            "cmd-watch-project",
                            "wildmatch",
                            "wildmatch_multislash",
                            "glob_generator"))),
                ImmutableMap.of(
                    "version",
                    "3.8.0",
                    "capabilities",
                    ImmutableMap.of(
                        "term-dirname", true,
                        "cmd-watch-project", true,
                        "wildmatch", true,
                        "wildmatch_multislash", true,
                        "glob_generator", false)),
                ImmutableList.of("watch-project", root),
                ImmutableMap.of("version", "3.8.0", "watch", root))),
        Paths.get(root),
        env,
        finder,
        new TestConsole(),
        clock);

    assertEquals("3.8.0", watchman.getVersion().get());
    assertEquals(
        ImmutableSet.of(
            Watchman.Capability.DIRNAME,
            Watchman.Capability.SUPPORTS_PROJECT_WATCH,
            Watchman.Capability.WILDMATCH_GLOB,
            Watchman.Capability.WILDMATCH_MULTISLASH),
        watchman.getCapabilities());
  }
}
