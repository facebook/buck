/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuleKeyLoggerListenerTest {

  private ProjectFilesystem projectFilesystem;
  private ExecutorService outputExecutor;
  private InvocationInfo info;
  private BuildRuleDurationTracker durationTracker;

  @Before
  public void setUp() throws InterruptedException, IOException {
    TemporaryFolder tempDirectory = new TemporaryFolder();
    tempDirectory.create();
    projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tempDirectory.getRoot().toPath());
    outputExecutor =
        MostExecutors.newSingleThreadExecutor(new CommandThreadFactory(getClass().getName()));
    info =
        InvocationInfo.of(
            new BuildId(),
            false,
            false,
            "topspin",
            ImmutableList.of(),
            ImmutableList.of(),
            tempDirectory.getRoot().toPath());
    durationTracker = new BuildRuleDurationTracker();
  }

  @Test
  public void testFileIsNotCreatedWithoutEvents() throws InterruptedException {
    RuleKeyLoggerListener listener = newInstance(1);
    listener.outputTrace(info.getBuildId());
    Assert.assertFalse(Files.exists(listener.getLogFilePath()));
  }

  @Test
  public void testSendingHttpCacheEvent() throws InterruptedException, IOException {
    RuleKeyLoggerListener listener = newInstance(1);
    listener.onArtifactCacheEvent(createArtifactCacheEvent(CacheResultType.MISS));
    listener.outputTrace(info.getBuildId());
    Assert.assertTrue(Files.exists(listener.getLogFilePath()));
    Assert.assertTrue(Files.size(listener.getLogFilePath()) > 0);
  }

  @Test
  public void testSendingInvalidHttpCacheEvent() throws InterruptedException {
    RuleKeyLoggerListener listener = newInstance(1);
    listener.onArtifactCacheEvent(createArtifactCacheEvent(CacheResultType.HIT));
    listener.outputTrace(info.getBuildId());
    Assert.assertFalse(Files.exists(listener.getLogFilePath()));
  }

  @Test
  public void testSendingBuildEvent() throws InterruptedException, IOException {
    RuleKeyLoggerListener listener = newInstance(1);
    listener.onBuildRuleEvent(createBuildEvent());
    listener.outputTrace(info.getBuildId());
    Assert.assertTrue(Files.exists(listener.getLogFilePath()));
    Assert.assertTrue(Files.size(listener.getLogFilePath()) > 0);
  }

  private BuildRuleEvent.Finished createBuildEvent() {
    BuildRule rule =
        new FakeBuildRule(
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    projectFilesystem.getRootPath(),
                    Optional.empty(),
                    "//topspin",
                    "//downtheline")));
    BuildRuleKeys keys = BuildRuleKeys.of(new RuleKey("1a1a1a"));
    BuildRuleEvent.Started started =
        TestEventConfigurator.configureTestEvent(BuildRuleEvent.started(rule, durationTracker));
    return BuildRuleEvent.finished(
        started,
        keys,
        null,
        null,
        Optional.empty(),
        null,
        false,
        null,
        null,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private HttpArtifactCacheEvent.Finished createArtifactCacheEvent(CacheResultType type) {
    return ArtifactCacheTestUtils.newFetchFinishedEvent(
        ArtifactCacheTestUtils.newFetchStartedEvent(new RuleKey("abababab42")),
        CacheResult.builder().setType(type).setCacheSource("random source").build());
  }

  private RuleKeyLoggerListener newInstance(int minLinesForAutoFlush) {
    return new RuleKeyLoggerListener(projectFilesystem, info, outputExecutor, minLinesForAutoFlush);
  }
}
