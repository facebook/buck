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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.listener.RenderingConsole;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
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
import javax.annotation.Nullable;
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
            null, new RuleKey(ruleKeyHash), CacheResult.hit("http", ArtifactCacheMode.http));

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

    ArtifactCache cache = new FakeArtifactCache(null, new RuleKey(ruleKeyHash), CacheResult.miss());

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
            null, new RuleKey(ruleKeyHash), CacheResult.hit("http", ArtifactCacheMode.http));

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

  @Test
  public void testRunCommandWithTargetNameAndFetchSuccessfully() throws Exception {
    final String targetName = "//foo/bar:bar";
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache =
        new FakeArtifactCache(
            targetName, new RuleKey(ruleKeyHash), CacheResult.hit("http", ArtifactCacheMode.http));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    Builder<String> arguments = ImmutableList.builder();
    arguments.add("fetch");

    Builder<Pair<String, String>> targetsWithRuleKeys = ImmutableList.builder();
    targetsWithRuleKeys.add(new Pair<>(targetName, ruleKeyHash));

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(arguments.build());
    cacheCommand.setTargetsWithRuleKeys(targetsWithRuleKeys.build());

    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.SUCCESS, exitCode);
    assertThat(
        console.getTextWrittenToStdErr(),
        containsString("Successfully downloaded artifact with id " + ruleKeyHash + " at "));
  }

  @Test
  public void testRunCommandWithMixedTargetNameWithNot() throws Exception {
    RecordingArtifactCache cache =
        new RecordingArtifactCache(CacheResult.hit("http", ArtifactCacheMode.http));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    Builder<String> arguments = ImmutableList.builder();
    arguments.add("fetch");
    final String rawRuleKey = "9ae9d0b9551c08a5119b608875b7753890aa3072";
    arguments.add(rawRuleKey);

    Builder<Pair<String, String>> targetsWithRuleKeys = ImmutableList.builder();
    final String target1 = "//foo:foo";
    final String ruleKey1 = "d93d0c9039fca504ec8f1f4d604215bc56b0229b";
    targetsWithRuleKeys.add(new Pair<>(target1, ruleKey1));
    final String target2 = "//bar:bar";
    final String ruleKey2 = "ea4513bc65306bb5dcd53fa1c7b6ff0754bd8ea2";
    targetsWithRuleKeys.add(new Pair<>(target2, ruleKey2));

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(arguments.build());
    cacheCommand.setTargetsWithRuleKeys(targetsWithRuleKeys.build());

    ExitCode exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(ExitCode.SUCCESS, exitCode);

    assertTrue(cache.requestedRawRuleKey(rawRuleKey));
    assertTrue(cache.requestedTargetWithRuleKey(target1, ruleKey1));
    assertTrue(cache.requestedTargetWithRuleKey(target2, ruleKey2));
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
            new RenderingConsole(clock, console),
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
            ImmutableSet.of(),
            ImmutableList.of());
    listener.register(eventBus);
    return listener;
  }

  /** Cache which only accepts one artifact (and returns the desired result), otherwise it throws */
  private static class FakeArtifactCache extends NoopArtifactCache {

    private final @Nullable String fullyQualifiedBuildTarget;
    private final RuleKey ruleKey;
    private final CacheResult cacheResult;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FakeArtifactCache(
        @Nullable String fullyQualifiedBuildTarget, RuleKey ruleKey, CacheResult cacheResult) {
      this.fullyQualifiedBuildTarget = fullyQualifiedBuildTarget;
      this.ruleKey = ruleKey;
      this.cacheResult = cacheResult;
    }

    @Override
    public ListenableFuture<CacheResult> fetchAsync(
        @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {

      if (!ruleKey.equals(this.ruleKey)) {
        throw new IllegalArgumentException();
      }

      if (target != null
          && !target.getFullyQualifiedName().equals(this.fullyQualifiedBuildTarget)) {
        throw new IllegalArgumentException();
      }

      return Futures.immediateFuture(cacheResult);
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        throw new IllegalStateException("Already closed");
      }
    }
  }

  /**
   * ArtifactCache which always returns the cacheResult and records what data was requested of it
   */
  private static class RecordingArtifactCache extends NoopArtifactCache {

    private final CacheResult cacheResult;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ImmutableList.Builder<Pair<Optional<BuildTarget>, RuleKey>> requestedArtifacts =
        new ImmutableList.Builder();
    private ImmutableList<Pair<Optional<BuildTarget>, RuleKey>> finalArtifacts;

    private RecordingArtifactCache(CacheResult cacheResult) {
      this.cacheResult = cacheResult;
    }

    public boolean requestedTargetWithRuleKey(String fullyQualifiedTarget, String ruleKey) {
      if (finalArtifacts == null) {
        throw new IllegalStateException("ArtifactCache must be closed before inspecting elements");
      }
      RuleKey rk = new RuleKey(ruleKey);
      for (Pair<Optional<BuildTarget>, RuleKey> pair : finalArtifacts) {
        // We're specifically looking for rulekeys with targets
        if (!pair.getFirst().isPresent()) {
          continue;
        }
        if (!rk.equals(pair.getSecond())) {
          continue;
        }
        BuildTarget target = pair.getFirst().get();
        if (fullyQualifiedTarget.equals(target.getFullyQualifiedName())) {
          return true;
        }
      }
      return false;
    }

    public boolean requestedRawRuleKey(String ruleKey) {
      if (finalArtifacts == null) {
        throw new IllegalStateException("ArtifactCache must be closed before inspecting elements");
      }
      RuleKey rk = new RuleKey(ruleKey);
      for (Pair<Optional<BuildTarget>, RuleKey> pair : finalArtifacts) {
        // We're specifically looking for raw rulekeys
        if (pair.getFirst().isPresent()) {
          continue;
        }

        if (rk.equals(pair.getSecond())) {
          return true;
        }
      }
      return false;
    }

    @Override
    public ListenableFuture<CacheResult> fetchAsync(
        @Nullable BuildTarget target, RuleKey ruleKey, LazyPath output) {
      requestedArtifacts.add(new Pair(Optional.ofNullable(target), ruleKey));
      return Futures.immediateFuture(cacheResult);
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        throw new IllegalStateException("Already closed");
      }
      finalArtifacts = requestedArtifacts.build();
    }
  }
}
