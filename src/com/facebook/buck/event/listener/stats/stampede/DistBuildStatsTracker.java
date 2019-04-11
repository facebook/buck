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
package com.facebook.buck.event.listener.stats.stampede;

import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.DistBuildStatusEvent.DistBuildStatus;
import com.facebook.buck.distributed.StampedeLocalBuildStatusEvent;
import com.facebook.buck.distributed.build_client.DistBuildRemoteProgressEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.listener.stats.cache.CacheRateStatsKeeper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.concurrent.GuardedBy;

/** Tracks distbuild related events and maintains stats about the build. */
public class DistBuildStatsTracker {

  /** The Listener gets callbacks for interesting events. */
  public interface Listener {
    void onWorkerJoined(BuildSlaveInfo slaveInfo);

    void onWorkerStatusChanged(BuildSlaveInfo slaveInfo);

    void onDistBuildStateChanged(BuildStatus distBuildState);
  }

  @VisibleForTesting volatile int distBuildTotalRulesCount = 0;
  @VisibleForTesting volatile int distBuildFinishedRulesCount = 0;

  private final Object distBuildStatusLock = new Object();

  @GuardedBy("distBuildStatusLock")
  private Optional<DistBuildStatus> distBuildStatus = Optional.empty();

  private BuildStatus distBuildState = BuildStatus.UNKNOWN;

  // TODO(cjhopman): This is so strange that we track two different "Status" objects.
  // Using LinkedHashMap because we want a predictable iteration order.
  @GuardedBy("distBuildStatusLock")
  private final Map<BuildSlaveRunId, BuildStatus> distBuildSlaveTracker = new LinkedHashMap<>();

  @GuardedBy("distBuildStatusLock")
  private final Map<BuildSlaveRunId, BuildSlaveStatus> distBuildSlaveStatusTracker =
      new LinkedHashMap<>();

  private final ConcurrentLinkedQueue<Listener> listeners = new ConcurrentLinkedQueue<>();

  private volatile StampedeLocalBuildStatusEvent stampedeLocalBuildStatus =
      new StampedeLocalBuildStatusEvent("init");

  /** Registers a listener. */
  public void registerListener(Listener listener) {
    listeners.add(listener);
  }

  /** Gets the current build slave statuses. */
  public ImmutableList<BuildSlaveStatus> getSlaveStatuses() {
    synchronized (distBuildStatusLock) {
      return ImmutableList.copyOf(distBuildSlaveStatusTracker.values());
    }
  }

  /** Gets the prefix for the local build line. */
  public String getLocalBuildLinePrefix() {
    return stampedeLocalBuildStatus.getLocalBuildLinePrefix();
  }

  /** Gets the current approximate progress of the distributed build. */
  public Optional<Double> getApproximateProgress() {
    synchronized (distBuildStatusLock) {
      if (distBuildTotalRulesCount == 0) {
        return Optional.of(0.0);
      }

      double buildRatio = (double) distBuildFinishedRulesCount / distBuildTotalRulesCount;
      return Optional.of(Math.floor(100 * buildRatio) / 100.0);
    }
  }

  /** Gets the current tracked distributed build status. */
  public DistBuildTrackedStatus getTrackedStatus() {
    synchronized (distBuildStatusLock) {
      ImmutableList.Builder<CacheRateStatsKeeper.CacheRateStatsUpdateEvent> slaveCacheStatsBuilder =
          ImmutableList.builder();

      if (distBuildStatus.isPresent()) {
        for (BuildSlaveStatus slaveStatus : distBuildStatus.get().getSlaveStatuses()) {

          if (slaveStatus.isSetCacheRateStats()) {
            slaveCacheStatsBuilder.add(
                CacheRateStatsKeeper.getCacheRateStatsUpdateEventFromSerializedStats(
                    slaveStatus.getCacheRateStats()));
          }
        }
      }
      return DistBuildTrackedStatus.of(
          distBuildStatus.isPresent(),
          distBuildStatus.flatMap(DistBuildStatus::getStatus),
          stampedeLocalBuildStatus.getStatus(),
          distBuildTotalRulesCount,
          distBuildFinishedRulesCount,
          slaveCacheStatsBuilder.build());
    }
  }

  @Subscribe
  private void onStampedeLocalBuildStatusEvent(StampedeLocalBuildStatusEvent event) {
    this.stampedeLocalBuildStatus = event;
  }

  /** Update distributed build progress. */
  @Subscribe
  private void onDistBuildProgressEvent(DistBuildRemoteProgressEvent event) {
    CoordinatorBuildProgress buildProgress = event.getBuildProgress();
    distBuildTotalRulesCount =
        buildProgress.getTotalRulesCount() - buildProgress.getSkippedRulesCount();
    distBuildFinishedRulesCount = buildProgress.getBuiltRulesCount();
  }

  @Subscribe
  private void onDistBuildStatusEvent(DistBuildStatusEvent event) {
    synchronized (distBuildStatusLock) {
      distBuildStatus = Optional.of(event.getStatus());

      BuildStatus previousState = this.distBuildState;
      this.distBuildState = event.getJob().getStatus();
      if (previousState != distBuildState) {
        listeners.forEach(listener -> listener.onDistBuildStateChanged(distBuildState));
      }

      for (BuildSlaveStatus slaveStatus : event.getStatus().getSlaveStatuses()) {
        // Don't track the status of failed or lost minions
        distBuildSlaveStatusTracker.put(slaveStatus.getBuildSlaveRunId(), slaveStatus);
      }

      for (BuildSlaveInfo slaveInfo : event.getJob().getBuildSlaves()) {
        if (!distBuildSlaveTracker.containsKey(slaveInfo.getBuildSlaveRunId())) {
          listeners.forEach(listener -> listener.onWorkerJoined(slaveInfo));
        } else {
          BuildStatus existingStatus = distBuildSlaveTracker.get(slaveInfo.getBuildSlaveRunId());
          if (!existingStatus.equals(slaveInfo.getStatus())) {
            listeners.forEach(listener -> listener.onWorkerStatusChanged(slaveInfo));
          }
        }
        distBuildSlaveTracker.put(slaveInfo.getBuildSlaveRunId(), slaveInfo.getStatus());
        if (slaveInfo.getStatus().equals(BuildStatus.FAILED)
            || slaveInfo.getStatus().equals(BuildStatus.LOST)) {
          synchronized (distBuildSlaveStatusTracker) {
            distBuildSlaveStatusTracker.remove(slaveInfo.buildSlaveRunId);
          }
        }
      }
    }
  }
}
