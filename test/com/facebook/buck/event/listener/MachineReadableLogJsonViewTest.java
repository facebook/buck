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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.artifact_cache.CacheCountersSummary;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.log.PerfTimesStats;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class MachineReadableLogJsonViewTest {

  private static final ObjectWriter WRITER =
      ObjectMappers.legacyCreate()
          .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
          .writerWithView(JsonViews.MachineReadableLog.class);

  private long timestamp;
  private long nanoTime;
  private long threadUserNanoTime;
  private long threadId;
  private BuildId buildId;
  private BuildRuleDurationTracker durationTracker;

  @Before
  public void setUp() {
    Clock clock = new DefaultClock();
    timestamp = clock.currentTimeMillis();
    nanoTime = clock.nanoTime();
    // Not using real value as not all JVMs will support thread user time.
    threadUserNanoTime = new Random().nextLong();
    threadId = 0;
    buildId = new BuildId("Test");
    durationTracker = new BuildRuleDurationTracker();
  }

  @Test
  public void testWatchmanEvents() throws Exception {
    WatchmanStatusEvent createEvent = WatchmanStatusEvent.fileCreation("filename_new");
    WatchmanStatusEvent deleteEvent = WatchmanStatusEvent.fileDeletion("filename_del");
    WatchmanStatusEvent overflowEvent = WatchmanStatusEvent.overflow("reason");

    // Configure the events so timestamps etc are there.
    createEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    deleteEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    overflowEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);

    assertJsonEquals("{%s,\"filename\":\"filename_new\"}", WRITER.writeValueAsString(createEvent));
    assertJsonEquals("{%s,\"filename\":\"filename_del\"}", WRITER.writeValueAsString(deleteEvent));
    assertJsonEquals("{%s,\"reason\":\"reason\"}", WRITER.writeValueAsString(overflowEvent));
  }

  @Test
  public void testParsingEvents() throws Exception {
    ParsingEvent symlink = ParsingEvent.symlinkInvalidation("target");
    ParsingEvent envChange = ParsingEvent.environmentalChange("diff");

    symlink.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    envChange.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);

    assertJsonEquals("{%s,\"path\":\"target\"}", WRITER.writeValueAsString(symlink));
    assertJsonEquals("{%s,\"diff\":\"diff\"}", WRITER.writeValueAsString(envChange));
  }

  @Test
  public void testBuildRuleEvent() throws IOException {
    BuildRule rule = new FakeBuildRule("//fake:rule");
    long durationMillis = 5;
    long durationNanos = 5 * 1000 * 1000;

    BuildRuleEvent.Started started = BuildRuleEvent.started(rule, durationTracker);
    started.configure(
        timestamp - durationMillis,
        nanoTime - durationNanos,
        threadUserNanoTime - durationNanos,
        threadId,
        buildId);
    BuildRuleEvent.Finished event =
        BuildRuleEvent.finished(
            started,
            BuildRuleKeys.builder()
                .setRuleKey(new RuleKey("aaaa"))
                .setInputRuleKey(Optional.of(new RuleKey("bbbb")))
                .build(),
            BuildRuleStatus.SUCCESS,
            CacheResult.of(
                CacheResultType.MISS,
                Optional.of("my-secret-source"),
                Optional.of(ArtifactCacheMode.dir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.of(HashCode.fromString("abcd42")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"status\":\"SUCCESS\",\"cacheResult\":{\"type\":\"MISS\","
            + "\"cacheSource\":\"my-secret-source\","
            + "\"cacheMode\":\"dir\"},"
            + String.format("\"duration\":{\"wallMillisDuration\":%d},", durationMillis)
            + "\"buildRule\":{\"type\":\"fake_build_rule\",\"name\":\"//fake:rule\"},"
            + "\"ruleKeys\":{\"ruleKey\":{\"hashCode\":\"aaaa\"},"
            + "\"inputRuleKey\":{\"hashCode\":\"bbbb\"}},"
            + "\"successTypeName\":\"BUILT_LOCALLY\","
            + "\"outputHash\":\"abcd42\"},",
        message);
  }

  @Test
  public void testPerfTimesStatsEvent() throws IOException {
    PerfTimesEventListener.PerfTimesEvent.Complete event =
        PerfTimesEventListener.PerfTimesEvent.complete(
            PerfTimesStats.builder()
                .setPythonTimeMs(4L)
                .setInitTimeMs(8L)
                .setProcessingTimeMs(15L)
                .setRulekeyTimeMs(100L)
                .setTotalRulekeyTimeMs(42L)
                .setActionGraphTimeMs(16L)
                .setBuildTimeMs(23L)
                .setInstallTimeMs(42L)
                .build());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);

    String message = WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,"
            + "\"perfTimesStats\":{"
            + "\"pythonTimeMs\":4,"
            + "\"initTimeMs\":8,"
            + "\"parseTimeMs\":0,"
            + "\"processingTimeMs\":15,"
            + "\"actionGraphTimeMs\":16,"
            + "\"rulekeyTimeMs\":100,"
            + "\"totalRulekeyTimeMs\":42,"
            + "\"fetchTimeMs\":0,"
            + "\"buildTimeMs\":23,"
            + "\"installTimeMs\":42}}",
        message);
  }

  @Test
  public void testCacheCountersSummary() throws IOException {
    CacheCountersSummary summary =
        CacheCountersSummary.of(
            ImmutableMap.of(
                ArtifactCacheMode.http,
                new AtomicInteger(1),
                ArtifactCacheMode.dir,
                new AtomicInteger(2)),
            ImmutableMap.of(
                ArtifactCacheMode.http,
                new AtomicInteger(1),
                ArtifactCacheMode.dir,
                new AtomicInteger(2)),
            3,
            3,
            0,
            5,
            0,
            new AtomicInteger(2),
            new AtomicInteger(0));

    assertJsonEquals(
        WRITER.writeValueAsString(summary),
        "{\"cacheHitsPerMode\":{\"dir\":2,\"http\":1},"
            + "\"cacheErrorsPerMode\":{\"dir\":2,\"http\":1},"
            + "\"totalCacheHits\":3,\"totalCacheErrors\":3,"
            + "\"totalCacheMisses\":0,\"totalCacheIgnores\":5,\""
            + "totalCacheLocalKeyUnchangedHits\":0,"
            + "\"successUploadCount\":2,\"failureUploadCount\":0}");
  }

  private void assertJsonEquals(String expected, String actual) {
    String commonHeader = String.format("\"timestamp\":%d", timestamp);
    assertThat(actual, new JsonMatcher(String.format(expected, commonHeader)));
  }
}
