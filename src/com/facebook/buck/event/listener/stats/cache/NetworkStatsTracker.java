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

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Scheduled;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.listener.stats.cache.CacheRateStatsKeeper.CacheRateStatsUpdateEvent;
import com.google.common.eventbus.Subscribe;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Tracks network related events and maintains stats about uploads/downloads/cache rate/etc. */
public class NetworkStatsTracker {

  /** The listener gets callbacks for interesting events. */
  public interface Listener {
    void onUploadsFinished();
  }

  /** Stats about remote artifact uploads. */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractRemoteArtifactUploadStats {
    int getStarted();

    int getFailed();

    int getUploaded();

    int getScheduled();

    long getTotalBytes();
  }

  private final AtomicInteger remoteArtifactUploadsScheduledCount = new AtomicInteger(0);
  private final AtomicInteger remoteArtifactUploadsStartedCount = new AtomicInteger(0);
  private final AtomicInteger remoteArtifactUploadedCount = new AtomicInteger(0);
  private final AtomicLong remoteArtifactTotalBytesUploaded = new AtomicLong(0);
  private final AtomicInteger remoteArtifactUploadFailedCount = new AtomicInteger(0);

  @Nullable private volatile HttpArtifactCacheEvent.Shutdown httpShutdownEvent;

  // TODO(cjhopman): CacheRateStatsKeeper is a bit odd here, it's actually interested in dir cache
  // and buck-out cache hits and just rules finished in general.
  private final CacheRateStatsKeeper cacheRateStatsKeeper;
  private final NetworkStatsKeeper networkStatsKeeper;

  private final ConcurrentLinkedQueue<Listener> listeners;

  public NetworkStatsTracker() {
    this.cacheRateStatsKeeper = new CacheRateStatsKeeper();
    this.networkStatsKeeper = new NetworkStatsKeeper();
    this.listeners = new ConcurrentLinkedQueue<>();
  }

  public void registerListener(Listener listener) {
    listeners.add(listener);
  }

  /** Get the current upload stats. */
  public RemoteArtifactUploadStats getRemoteArtifactUploadStats() {
    return RemoteArtifactUploadStats.builder()
        .setStarted(remoteArtifactUploadsStartedCount.get())
        .setFailed(remoteArtifactUploadFailedCount.get())
        .setUploaded(remoteArtifactUploadedCount.get())
        .setScheduled(remoteArtifactUploadsScheduledCount.get())
        .setTotalBytes(remoteArtifactTotalBytesUploaded.get())
        .build();
  }

  public boolean haveUploadsStarted() {
    return remoteArtifactUploadsScheduledCount.get() > 0;
  }

  public boolean haveUploadsFinished() {
    return httpShutdownEvent != null;
  }

  /** Get the current cache rate stats. */
  public CacheRateStatsUpdateEvent getCacheRateStats() {
    return cacheRateStatsKeeper.getStats();
  }

  /** Get the current download stats. */
  public RemoteDownloadStats getRemoteDownloadStats() {
    return networkStatsKeeper.getRemoteDownloadStats();
  }

  @Subscribe
  private void buildRuleFinished(BuildRuleEvent.Finished finished) {
    cacheRateStatsKeeper.buildRuleFinished(finished);
  }

  @Subscribe
  private void onHttpArtifactCacheScheduledEvent(Scheduled event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      remoteArtifactUploadsScheduledCount.incrementAndGet();
    }
  }

  @Subscribe
  private void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      remoteArtifactUploadsStartedCount.incrementAndGet();
    }
  }

  @Subscribe
  private void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    switch (event.getOperation()) {
      case MULTI_FETCH:
      case FETCH:
        if (event.getCacheResult().map(res -> res.getType().isSuccess()).orElse(false)) {
          // TODO(cjhopman): Does this count two-level artifacts as two things?
          // TODO(cjhopman): I think we should rename this artifacts->something. I think that
          // "artifacts" is generally understood to mean "files" and we upload archives of many
          // files. Maybe we could just use "archives" but that might be confusing, too.
          networkStatsKeeper.incrementRemoteDownloadedArtifactsCount();
          event
              .getCacheResult()
              .get()
              .artifactSizeBytes()
              .ifPresent(networkStatsKeeper::addRemoteDownloadedArtifactsBytes);
        }
        break;
      case STORE:
        if (event.getStoreData().wasStoreSuccessful().orElse(false)) {
          remoteArtifactUploadedCount.incrementAndGet();
          event
              .getStoreData()
              .getArtifactSizeBytes()
              .ifPresent(remoteArtifactTotalBytesUploaded::addAndGet);
        } else {
          remoteArtifactUploadFailedCount.incrementAndGet();
        }
        break;
      case MULTI_CONTAINS:
        break;
    }
  }

  @Subscribe
  private void onHttpArtifactCacheShutdownEvent(HttpArtifactCacheEvent.Shutdown event) {
    httpShutdownEvent = event;
    if (haveUploadsStarted()) {
      listeners.forEach(Listener::onUploadsFinished);
    }
  }

  @Subscribe
  private void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    cacheRateStatsKeeper.ruleCountCalculated(calculated);
  }

  @Subscribe
  private void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    cacheRateStatsKeeper.ruleCountUpdated(updated);
  }
}
