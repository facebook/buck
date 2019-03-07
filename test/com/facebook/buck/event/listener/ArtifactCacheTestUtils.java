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

package com.facebook.buck.event.listener;

import static com.facebook.buck.event.TestEventConfigurator.configureTestEventAtTime;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent.StoreType;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ArtifactCacheTestUtils {

  private ArtifactCacheTestUtils() {}

  static HttpArtifactCacheEvent.Started newFetchStartedEvent(BuildTarget target, RuleKey rulekey) {
    return newFetchStartedEventImpl(target, rulekey, false);
  }

  public static HttpArtifactCacheEvent.Started newFetchConfiguredStartedEvent(
      BuildTarget target, RuleKey rulekey) {
    return newFetchStartedEventImpl(target, rulekey, true);
  }

  private static HttpArtifactCacheEvent.Started newFetchStartedEventImpl(
      BuildTarget target, RuleKey ruleKey, boolean configured) {
    HttpArtifactCacheEvent.Started started =
        HttpArtifactCacheEvent.newFetchStartedEvent(target, ruleKey);
    if (configured) {
      started.configure(-1, -1, -1, -1, new BuildId());
    }
    return started;
  }

  static HttpArtifactCacheEvent.Started newUploadStartedEvent(BuildId buildId) {
    return newUploadStartedEventImpl(buildId, Optional.empty(), ImmutableSet.of(), true);
  }

  public static HttpArtifactCacheEvent.Scheduled newUploadScheduledEvent(
      BuildId buildId,
      Optional<BuildTarget> target,
      ImmutableSet<RuleKey> ruleKeys,
      StoreType storeType,
      boolean configureEvent) {

    HttpArtifactCacheEvent.Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(target, ruleKeys, storeType);
    if (configureEvent) {
      scheduled.configure(1, 0, 0, 0, buildId);
    }
    return scheduled;
  }

  public static HttpArtifactCacheEvent.Started newUploadStartedEvent(
      HttpArtifactCacheEvent.Scheduled scheduledEvent, boolean configureEvent) {
    HttpArtifactCacheEvent.Started started =
        HttpArtifactCacheEvent.newStoreStartedEvent(scheduledEvent);
    if (configureEvent) {
      started.configure(1, 0, 0, 0, scheduledEvent.getBuildId());
    }
    return started;
  }

  public static HttpArtifactCacheEvent.Started newUploadConfiguredStartedEvent(
      BuildId buildId, Optional<BuildTarget> target, ImmutableSet<RuleKey> ruleKeys) {
    return newUploadStartedEventImpl(buildId, target, ruleKeys, true);
  }

  static HttpArtifactCacheEvent.Started newUploadStartedEvent(
      BuildId buildId, Optional<BuildTarget> target, ImmutableSet<RuleKey> ruleKeys) {
    return newUploadStartedEventImpl(buildId, target, ruleKeys, false);
  }

  private static HttpArtifactCacheEvent.Started newUploadStartedEventImpl(
      BuildId buildId,
      Optional<BuildTarget> target,
      ImmutableSet<RuleKey> ruleKeys,
      boolean configureEvent) {
    HttpArtifactCacheEvent.Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(target, ruleKeys, StoreType.ARTIFACT);
    HttpArtifactCacheEvent.Started event = HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
    if (configureEvent) {
      event.configure(1, 0, 0, 0, buildId);
    }
    return event;
  }

  static HttpArtifactCacheEvent.Scheduled postStoreScheduled(
      BuckEventBus eventBus, long threadId, BuildTarget target, long timeInMs) {
    HttpArtifactCacheEvent.Scheduled storeScheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of(target), ImmutableSet.of(), StoreType.ARTIFACT);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(storeScheduled, timeInMs, TimeUnit.MILLISECONDS, threadId));
    return storeScheduled;
  }

  static HttpArtifactCacheEvent.Started postStoreStarted(
      BuckEventBus eventBus,
      long threadId,
      long timeInMs,
      HttpArtifactCacheEvent.Scheduled storeScheduled) {
    HttpArtifactCacheEvent.Started storeStartedOne =
        HttpArtifactCacheEvent.newStoreStartedEvent(storeScheduled);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(storeStartedOne, timeInMs, TimeUnit.MILLISECONDS, threadId));
    return storeStartedOne;
  }

  static void postStoreFinished(
      BuckEventBus eventBus,
      long threadId,
      long artifactSizeInBytes,
      long timeInMs,
      boolean success,
      HttpArtifactCacheEvent.Started storeStartedOne) {
    HttpArtifactCacheEvent.Finished.Builder storeFinished =
        HttpArtifactCacheEvent.newFinishedEventBuilder(storeStartedOne);
    storeFinished
        .getStoreBuilder()
        .setWasStoreSuccessful(success)
        .setStoreType(StoreType.ARTIFACT)
        .setArtifactSizeBytes(artifactSizeInBytes);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(storeFinished.build(), timeInMs, TimeUnit.MILLISECONDS, threadId));
  }

  public static HttpArtifactCacheEvent.Finished newFinishedEvent(
      HttpArtifactCacheEvent.Started startedEvent, boolean configureEvent) {
    HttpArtifactCacheEvent.Finished event =
        HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent).build();
    if (configureEvent) {
      event.configure(1, 0, 0, 0, startedEvent.getBuildId());
    }
    return event;
  }

  public static HttpArtifactCacheEvent.Finished newFetchFinishedEvent(
      HttpArtifactCacheEvent.Started started, CacheResult cacheResult) {
    HttpArtifactCacheEvent.Finished.Builder builder =
        HttpArtifactCacheEvent.newFinishedEventBuilder(started);
    builder.getFetchBuilder().setFetchResult(cacheResult);
    return builder.build();
  }
}
