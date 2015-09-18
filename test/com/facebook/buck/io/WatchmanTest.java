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

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class WatchmanTest {

  private String root = "/some/root";
  private String exe = "/opt/bin/watchman";
  private FakeExecutableFinder finder = new FakeExecutableFinder(Paths.get(exe));
  private ImmutableMap<String, String> env = ImmutableMap.of();

  @Test
  public void shouldReturnEmptyWatchmanIfVersionCheckFails() throws InterruptedException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "version"),
                FakeListeningProcessState.ofExit(1))
            .build(),
        clock);

    Watchman watchman = Watchman.build(
        executor, Paths.get(root), env, finder, new TestConsole(), clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }

  @Test
  public void shouldNotUseWatchProjectIfNotAvailable() throws InterruptedException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "version"),
                FakeListeningProcessState.ofStdout("{\"version\":\"3.0.0\"}"),
                FakeListeningProcessState.ofExit(0))
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "watch", root),
                FakeListeningProcessState.ofStdout(
                    "{\"version\":\"3.0.0\",\"watch\":\"" + root + "\"}"),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);

    Watchman watchman = Watchman.build(
        executor, Paths.get(root), env, finder, new TestConsole(), clock);

    assertEquals("3.0.0", watchman.getVersion().get());
    assertEquals(root, watchman.getWatchRoot().get());
    assertEquals(Optional.absent(), watchman.getProjectPrefix());
  }

  @Test
  public void successfulExecutionPopulatesAWatchmanInstance() throws InterruptedException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "version"),
                FakeListeningProcessState.ofStdout("{\"version\":\"3.4.0\"}"),
                FakeListeningProcessState.ofExit(0))
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "watch-project", root),
                FakeListeningProcessState.ofStdout(
                    "{\"version\":\"3.4.0\",\"watch\":\"" + root + "\"}"),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor, Paths.get(root), env, finder, new TestConsole(), clock);

    assertEquals("3.4.0", watchman.getVersion().get());
    assertEquals(root, watchman.getWatchRoot().get());
    assertEquals(Optional.absent(), watchman.getProjectPrefix());
  }

  @Test
  public void watchmanVersionTakingThirtySecondsReturnsEmpty() throws InterruptedException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "version"),
                FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(30)),
                FakeListeningProcessState.ofStdout("{\"version\":\"3.4.0\"}"),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor, Paths.get(root), env, finder, new TestConsole(), clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }

  @Test
  public void watchmanWatchProjectTakingThirtySecondsReturnsEmpty() throws InterruptedException {
    SettableFakeClock clock = new SettableFakeClock(0, 0);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder()
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "version"),
                FakeListeningProcessState.ofStdout("{\"version\":\"3.4.0\"}"),
                FakeListeningProcessState.ofExit(0))
            .putAll(
                ProcessExecutorParams.ofCommand(exe, "watch-project", root),
                FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(30)),
                FakeListeningProcessState.ofStdout(
                    "{\"version\":\"3.4.0\",\"watch\":\"" + root + "\"}"),
                FakeListeningProcessState.ofExit(0))
            .build(),
        clock);
    Watchman watchman = Watchman.build(
        executor, Paths.get(root), env, finder, new TestConsole(), clock);

    assertEquals(Watchman.NULL_WATCHMAN, watchman);
  }
}
