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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.newCapture;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.Console;
import com.google.common.collect.ImmutableList;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class CacheCommandTest extends EasyMockSupport {

  @Test
  public void testRunCommandWithNoArguments()
      throws IOException, InterruptedException {
    Console console = createMock(Console.class);
    console.printErrorText("No cache keys specified.");
    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting
        .builder()
        .setConsole(console)
        .build();
    CacheCommand cacheCommand = new CacheCommand(commandRunnerParams);
    BuckConfig buckConfig = createMock(BuckConfig.class);

    replayAll();

    CacheCommandOptions options = new CacheCommandOptions(buckConfig);
    int exitCode = cacheCommand.runCommandWithOptions(options);
    assertEquals(1, exitCode);

    verifyAll();
  }

  @Test
  public void testRunCommandAndFetchArtifactsSuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(
        cache.fetch(
            eq(new RuleKey(ruleKeyHash)),
            isA(File.class)))
        .andReturn(CacheResult.CASSANDRA_HIT);
    ArtifactCacheFactory artifactCacheFactory = new InstanceArtifactCacheFactory(cache);

    Console console = createMock(Console.class);
    Capture<String> successMessage = newCapture();
    console.printSuccess(capture(successMessage));

    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder()
        .setConsole(console)
        .setArtifactCacheFactory(artifactCacheFactory)
        .build();

    CacheCommand cacheCommand = new CacheCommand(commandRunnerParams);

    BuckConfig buckConfig = createMock(BuckConfig.class);

    replayAll();

    CacheCommandOptions options = new CacheCommandOptions(buckConfig);
    options.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.runCommandWithOptions(options);
    assertEquals(0, exitCode);
    assertThat(successMessage.getValue(),
        startsWith("Successfully downloaded artifact with id " + ruleKeyHash + " at "));

    verifyAll();
  }

  @Test
  public void testRunCommandAndFetchArtifactsUnsuccessfully()
      throws IOException, InterruptedException {
    final String ruleKeyHash = "b64009ae3762a42a1651c139ec452f0d18f48e21";

    ArtifactCache cache = createMock(ArtifactCache.class);
    expect(
        cache.fetch(
            eq(new RuleKey(ruleKeyHash)),
            isA(File.class)))
        .andReturn(CacheResult.MISS);
    ArtifactCacheFactory artifactCacheFactory = new InstanceArtifactCacheFactory(cache);

    Console console = createMock(Console.class);
    console.printErrorText("Failed to retrieve an artifact with id " + ruleKeyHash + ".");

    CommandRunnerParams commandRunnerParams = CommandRunnerParamsForTesting.builder()
        .setConsole(console)
        .setArtifactCacheFactory(artifactCacheFactory)
        .build();

    CacheCommand cacheCommand = new CacheCommand(commandRunnerParams);

    BuckConfig buckConfig = createMock(BuckConfig.class);

    replayAll();

    CacheCommandOptions options = new CacheCommandOptions(buckConfig);
    options.setArguments(ImmutableList.of(ruleKeyHash));
    int exitCode = cacheCommand.runCommandWithOptions(options);
    assertEquals(1, exitCode);

    verifyAll();
  }
}
