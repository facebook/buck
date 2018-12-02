/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class CacheCommandTest {

  private void testRunCommandWithNoArgumentsImpl(boolean fetchPrefix) throws Exception {
    TestConsole console = new TestConsole();
    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).build();
    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(
        fetchPrefix ? Collections.singletonList("fetch") : Collections.emptyList());

    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.COMMANDLINE_ERROR, exitCode);

    if (!CacheCommand.MUTE_FETCH_SUBCOMMAND_WARNING) {
      assertThat(
          console.getTextWrittenToStdErr(),
          fetchPrefix ? not(containsString("deprecated")) : containsString("deprecated"));
    }
  }

  @Test(expected = CommandLineException.class)
  public void testRunCommandWithNoArguments() throws Exception {
    testRunCommandWithNoArgumentsImpl(false);
  }

  @Test(expected = CommandLineException.class)
  public void testRunCommandFetchWithNoArguments() throws Exception {
    testRunCommandWithNoArgumentsImpl(true);
  }

  private void testRunCommandAndFetchArtifactsSuccessfullyImpl(boolean fetchPrefix)
      throws Exception {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache =
        new FakeArtifactCache(
            new RuleKey(ruleKeyHash), CacheResult.hit("http", ArtifactCacheMode.http));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    Builder<String> arguments = ImmutableList.builder();
    if (fetchPrefix) {
      arguments.add("fetch");
    }
    arguments.add(ruleKeyHash);

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(arguments.build());
    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.SUCCESS, exitCode);
    assertThat(
        console.getTextWrittenToStdErr(),
        containsString("Successfully downloaded artifact with id " + ruleKeyHash + " at "));

    if (!CacheCommand.MUTE_FETCH_SUBCOMMAND_WARNING) {
      assertThat(
          console.getTextWrittenToStdErr(),
          fetchPrefix ? not(containsString("deprecated")) : containsString("deprecated"));
    }
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfully() throws Exception {
    testRunCommandAndFetchArtifactsSuccessfullyImpl(false);
  }

  @Test
  public void testRunCommandFetchAndFetchArtifactsSuccessfully() throws Exception {
    testRunCommandAndFetchArtifactsSuccessfullyImpl(true);
  }

  @Test
  public void testRunCommandAndFetchArtifactsUnsuccessfully() throws Exception {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = new FakeArtifactCache(new RuleKey(ruleKeyHash), CacheResult.miss());

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.BUILD_ERROR, exitCode);
    assertThat(
        console.getTextWrittenToStdErr(),
        containsString("Failed to retrieve an artifact with id " + ruleKeyHash + " (miss)."));
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfullyAndSuperConsole() throws Exception {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache =
        new FakeArtifactCache(
            new RuleKey(ruleKeyHash), CacheResult.hit("http", ArtifactCacheMode.http));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();
    SuperConsoleEventBusListener listener =
        createSuperConsole(
            console, commandRunnerParams.getClock(), commandRunnerParams.getBuckEventBus());

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.SUCCESS, exitCode);
    ImmutableList<String> lines =
        listener.createRenderLinesAtTime(commandRunnerParams.getClock().currentTimeMillis());
    StringBuilder strBuilder = new StringBuilder();
    for (String line : lines) {
      strBuilder.append(line);
      strBuilder.append("\n");
    }
    assertThat(strBuilder.toString(), containsString("Downloaded"));
  }

  private SuperConsoleEventBusListener createSuperConsole(
      Console console, Clock clock, BuckEventBus eventBus) {
    TimeZone timeZone = TimeZone.getTimeZone("UTC");
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path logPath = vfs.getPath("log.txt");
    SuperConsoleConfig emptySuperConsoleConfig =
        new SuperConsoleConfig(FakeBuckConfig.builder().build());
    TestResultSummaryVerbosity silentSummaryVerbosity = TestResultSummaryVerbosity.of(false, false);
    SuperConsoleEventBusListener listener =
        new SuperConsoleEventBusListener(
            emptySuperConsoleConfig,
            console,
            clock,
            silentSummaryVerbosity,
            new DefaultExecutionEnvironment(
                EnvVariablesProvider.getSystemEnv(), System.getProperties()),
            Locale.US,
            logPath,
            timeZone,
            0L,
            0L,
            1000L,
            false,
            new BuildId("1234-5678"),
            false,
            Optional.empty(),
            ImmutableList.of());
    eventBus.register(listener);
    return listener;
  }

  private static class FakeArtifactCache extends NoopArtifactCache {

    private final RuleKey ruleKey;
    private final CacheResult cacheResult;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FakeArtifactCache(RuleKey ruleKey, CacheResult cacheResult) {
      this.ruleKey = ruleKey;
      this.cacheResult = cacheResult;
    }

    @Override
    public ListenableFuture<CacheResult> fetchAsync(
        BuildTarget target, RuleKey ruleKey, LazyPath output) {
      if (ruleKey.equals(this.ruleKey)) {
        return Futures.immediateFuture(cacheResult);
      }
      throw new IllegalArgumentException();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        throw new IllegalStateException("Already closed");
      }
    }
  }
}
