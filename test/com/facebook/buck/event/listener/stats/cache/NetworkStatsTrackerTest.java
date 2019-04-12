/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.event.listener.stats.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent.StoreType;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Finished.Builder;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Scheduled;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Started;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent.RuleCountCalculated;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent.Finished;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.listener.stats.cache.CacheRateStatsKeeper.CacheRateStatsUpdateEvent;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import org.junit.Test;

public class NetworkStatsTrackerTest {
  @Test
  public void downloadStats() {
    NetworkStatsTracker tracker = new NetworkStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    Started started =
        HttpArtifactCacheEvent.newFetchStartedEvent(
            BuildTargetFactory.newInstance("//:test"), new RuleKey(HashCode.fromInt(0)));
    eventBus.post(started);

    assertEquals(RemoteDownloadStats.of(0, 0), tracker.getRemoteDownloadStats());

    Builder finishedBuilder = HttpArtifactCacheEvent.newFinishedEventBuilder(started);
    finishedBuilder
        .getFetchBuilder()
        .setFetchResult(
            CacheResult.hit("dontcare", ArtifactCacheMode.http).withArtifactSizeBytes(100))
        .setArtifactSizeBytes(100);
    eventBus.post(finishedBuilder.build());

    assertEquals(RemoteDownloadStats.of(1, 100), tracker.getRemoteDownloadStats());

    started =
        HttpArtifactCacheEvent.newFetchStartedEvent(
            BuildTargetFactory.newInstance("//:test"), new RuleKey(HashCode.fromInt(0)));
    eventBus.post(started);

    assertEquals(RemoteDownloadStats.of(1, 100), tracker.getRemoteDownloadStats());

    finishedBuilder = HttpArtifactCacheEvent.newFinishedEventBuilder(started);
    finishedBuilder
        .getFetchBuilder()
        .setFetchResult(
            CacheResult.hit("dontcare", ArtifactCacheMode.http).withArtifactSizeBytes(200))
        .setArtifactSizeBytes(200);
    eventBus.post(finishedBuilder.build());

    assertEquals(RemoteDownloadStats.of(2, 300), tracker.getRemoteDownloadStats());
  }

  @Test
  public void cacheRateStats() {
    NetworkStatsTracker tracker = new NetworkStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    assertEquals(
        new CacheRateStatsUpdateEvent(0, 0, 0, 0, 0).getValueString(),
        tracker.getCacheRateStats().getValueString());

    eventBus.post(RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 1000));
    assertEquals(
        new CacheRateStatsUpdateEvent(0, 0, 0, 1000, 0).getValueString(),
        tracker.getCacheRateStats().getValueString());

    eventBus.post(RuleCountCalculated.unskippedRuleCountUpdated(900));

    assertEquals(
        new CacheRateStatsUpdateEvent(0, 0, 0, 900, 0).getValueString(),
        tracker.getCacheRateStats().getValueString());

    BuildRuleEvent.Started started = ruleStarted("//:test");
    eventBus.post(started);
    eventBus.post(ruleFinished(started, CacheResult.miss()));

    assertEquals(
        new CacheRateStatsUpdateEvent(1, 0, 0, 900, 1).getValueString(),
        tracker.getCacheRateStats().getValueString());

    started = ruleStarted("//:test");
    eventBus.post(started);
    eventBus.post(ruleFinished(started, CacheResult.skipped()));

    assertEquals(
        new CacheRateStatsUpdateEvent(1, 0, 0, 900, 2).getValueString(),
        tracker.getCacheRateStats().getValueString());

    started = ruleStarted("//:test");
    eventBus.post(started);
    eventBus.post(ruleFinished(started, CacheResult.hit("dontcare", ArtifactCacheMode.dir)));

    assertEquals(
        new CacheRateStatsUpdateEvent(1, 0, 1, 900, 3).getValueString(),
        tracker.getCacheRateStats().getValueString());

    started = ruleStarted("//:test");
    eventBus.post(started);
    eventBus.post(ruleFinished(started, CacheResult.localKeyUnchangedHit()));

    // localy key unchanged doesn't count as updated.
    assertEquals(
        new CacheRateStatsUpdateEvent(1, 0, 1, 900, 3).getValueString(),
        tracker.getCacheRateStats().getValueString());

    started = ruleStarted("//:test");
    eventBus.post(started);
    eventBus.post(
        ruleFinished(started, CacheResult.error("dontcare", ArtifactCacheMode.dir, "dontcare")));

    assertEquals(
        new CacheRateStatsUpdateEvent(1, 1, 1, 900, 4).getValueString(),
        tracker.getCacheRateStats().getValueString());
  }

  @Nonnull
  public BuildRuleEvent.Started ruleStarted(String target) {
    return BuildRuleEvent.started(new FakeBuildRule(target), new BuildRuleDurationTracker());
  }

  @Nonnull
  public Finished ruleFinished(BuildRuleEvent.Started started, CacheResult cacheResult) {
    return BuildRuleEvent.finished(
        started,
        BuildRuleKeys.of(new RuleKey(HashCode.fromInt(0))),
        BuildRuleStatus.SUCCESS,
        cacheResult,
        Optional.empty(),
        Optional.empty(),
        UploadToCacheResultType.CACHEABLE_READONLY_CACHE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  public void uploadStats() {
    NetworkStatsTracker tracker = new NetworkStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    AtomicBoolean uploadsFinished = new AtomicBoolean();
    tracker.registerListener(() -> uploadsFinished.set(true));

    assertFalse(uploadsFinished.get());
    assertFalse(tracker.haveUploadsStarted());
    assertFalse(tracker.haveUploadsFinished());
    assertEquals(uploadStats(0, 0, 0, 0, 0), tracker.getRemoteArtifactUploadStats());

    eventBus.post(
        HttpArtifactCacheEvent.newFetchStartedEvent(
            BuildTargetFactory.newInstance("//:test"), new RuleKey(HashCode.fromInt(0))));

    assertFalse(tracker.haveUploadsStarted());
    assertEquals(uploadStats(0, 0, 0, 0, 0), tracker.getRemoteArtifactUploadStats());

    Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.empty(), ImmutableSet.of(), StoreType.ARTIFACT);
    eventBus.post(scheduled);

    assertTrue(tracker.haveUploadsStarted());
    assertEquals(uploadStats(0, 1, 0, 0, 0), tracker.getRemoteArtifactUploadStats());

    Started started = HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
    eventBus.post(started);
    assertTrue(tracker.haveUploadsStarted());
    assertEquals(uploadStats(0, 1, 1, 0, 0), tracker.getRemoteArtifactUploadStats());

    Builder finishedBuilder = HttpArtifactCacheEvent.newFinishedEventBuilder(started);
    finishedBuilder.getStoreBuilder().setWasStoreSuccessful(true).setArtifactSizeBytes(100);

    eventBus.post(finishedBuilder.build());
    assertTrue(tracker.haveUploadsStarted());
    assertFalse(tracker.haveUploadsFinished());
    assertEquals(uploadStats(0, 1, 1, 1, 100), tracker.getRemoteArtifactUploadStats());

    scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.empty(), ImmutableSet.of(), StoreType.ARTIFACT);
    eventBus.post(scheduled);

    assertTrue(tracker.haveUploadsStarted());
    assertEquals(uploadStats(0, 2, 1, 1, 100), tracker.getRemoteArtifactUploadStats());

    started = HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
    eventBus.post(started);
    assertTrue(tracker.haveUploadsStarted());
    assertFalse(uploadsFinished.get());
    assertEquals(uploadStats(0, 2, 2, 1, 100), tracker.getRemoteArtifactUploadStats());

    finishedBuilder = HttpArtifactCacheEvent.newFinishedEventBuilder(started);
    finishedBuilder.getStoreBuilder().setWasStoreSuccessful(false);

    eventBus.post(finishedBuilder.build());
    assertTrue(tracker.haveUploadsStarted());
    assertFalse(tracker.haveUploadsFinished());
    assertEquals(uploadStats(1, 2, 2, 1, 100), tracker.getRemoteArtifactUploadStats());

    eventBus.post(HttpArtifactCacheEvent.newShutdownEvent());

    assertTrue(tracker.haveUploadsFinished());
    assertTrue(uploadsFinished.get());
  }

  private RemoteArtifactUploadStats uploadStats(
      int failed, int scheduled, int started, int uploaded, long totalBytes) {
    return RemoteArtifactUploadStats.builder()
        .setFailed(failed)
        .setScheduled(scheduled)
        .setStarted(started)
        .setUploaded(uploaded)
        .setTotalBytes(totalBytes)
        .build();
  }
}
