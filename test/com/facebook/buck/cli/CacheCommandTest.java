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

import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.TestConsole;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class CacheCommandTest extends EasyMockSupport {

  @Test
  public void testRunCommandWithNoArguments() throws IOException, InterruptedException {
    TestConsole console = new TestConsole();
    console.printErrorText("No cache keys specified.");
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .setConsole(console)
        .build();
    CacheCommand cacheCommand = new CacheCommand();
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(1, exitCode);
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(
        cache.fetch(
            eq(new RuleKey(ruleKeyHash)),
            isA(Path.class)))
        .andReturn(CacheResult.hit("http"));

    TestConsole console = new TestConsole();

    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder()
        .setConsole(console)
        .setArtifactCache(cache)
        .build();

    replayAll();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(0, exitCode);
    assertThat(
        console.getTextWrittenToStdErr(),
        startsWith("Successfully downloaded artifact with id " + ruleKeyHash + " at "));
  }

  @Test
  public void testRunCommandAndFetchArtifactsUnsuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(
        cache.fetch(
            eq(new RuleKey(ruleKeyHash)),
            isA(Path.class)))
        .andReturn(CacheResult.miss());

    TestConsole console = new TestConsole();
    console.printErrorText("Failed to retrieve an artifact with id " + ruleKeyHash + ".");

    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder()
        .setConsole(console)
        .setArtifactCache(cache)
        .build();

    replayAll();

    CacheCommand cacheCommand = new CacheCommand();
    cacheCommand.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.run(commandRunnerParams);
    assertEquals(1, exitCode);
  }
}
