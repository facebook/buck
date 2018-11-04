/*
 * Copyright 2017-present Facebook, Inc.
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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheDeleteResult;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.Test;

public class CacheDeleteCommandTest {
  @Test(expected = CommandLineException.class)
  public void testRunCommandWithNoArguments() throws Exception {
    TestConsole console = new TestConsole();
    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).build();
    CacheDeleteCommand cacheDeleteCommand = new CacheDeleteCommand();
    ExitCode exitCode = cacheDeleteCommand.run(commandRunnerParams);
    assertEquals(ExitCode.COMMANDLINE_ERROR, exitCode);
  }

  @Test
  public void testRunCommandAndDeleteArtifactsSuccessfully() throws Exception {
    String[] ruleKeyHashes = {
      "b64009ae3762a42a1651c139ec452f0d18f48e21", "9837098ab8745dabcb64009ae3762a42a16545a2",
    };

    List<RuleKey> ruleKeys =
        Arrays.stream(ruleKeyHashes).map(RuleKey::new).collect(Collectors.toList());

    CacheDeleteResult cacheDeleteResult =
        CacheDeleteResult.builder().setCacheNames(ImmutableList.of("test")).build();
    ArtifactCache cache =
        new FakeArtifactCache(ruleKeys, Futures.immediateFuture(cacheDeleteResult));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    CacheDeleteCommand cacheDeleteCommand = new CacheDeleteCommand();
    cacheDeleteCommand.setArguments(ImmutableList.copyOf(ruleKeyHashes));
    ExitCode exitCode = cacheDeleteCommand.run(commandRunnerParams);
    assertEquals(ExitCode.SUCCESS, exitCode);
    assertThat(console.getTextWrittenToStdErr(), startsWith("Successfully deleted 2 artifacts"));
  }

  @Test
  public void testRunCommandAndDeleteArtifactsUnsuccessfully() throws Exception {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache =
        new FakeArtifactCache(
            Collections.singletonList(new RuleKey(ruleKeyHash)),
            Futures.immediateFailedFuture(new RuntimeException("test failure")));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams =
        CommandRunnerParamsForTesting.builder().setConsole(console).setArtifactCache(cache).build();

    CacheDeleteCommand cacheDeleteCommand = new CacheDeleteCommand();
    cacheDeleteCommand.setArguments(ImmutableList.of(ruleKeyHash));
    ExitCode exitCode = cacheDeleteCommand.run(commandRunnerParams);
    assertEquals(ExitCode.FATAL_GENERIC, exitCode);
    assertThat(console.getTextWrittenToStdErr(), startsWith("Failed to delete artifacts."));
  }

  private static class FakeArtifactCache extends NoopArtifactCache {

    private final List<RuleKey> ruleKeys;
    private final ListenableFuture<CacheDeleteResult> cacheResult;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FakeArtifactCache(
        List<RuleKey> ruleKeys, ListenableFuture<CacheDeleteResult> cacheResult) {
      this.ruleKeys = ruleKeys;
      this.cacheResult = cacheResult;
    }

    @Override
    public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
      if (ruleKeys.equals(this.ruleKeys)) {
        return cacheResult;
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
