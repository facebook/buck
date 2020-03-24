/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.google.common.eventbus.Subscribe;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This listener adds up the amount of time that is spent in building artifacts that were built as a
 * result of cache misses and prints to the console if that amount goes over a given threshold.
 */
public class CacheMissBuildTimeListener implements BuckEventListener {
  BuckEventBus buckEventBus;
  RuleKeyCheckListenerConfig config;
  AtomicLong timeBuildingArtifactsInMillis;

  public CacheMissBuildTimeListener(BuckEventBus buckEventBus, RuleKeyCheckListenerConfig config) {
    this.buckEventBus = buckEventBus;
    this.config = config;
    this.timeBuildingArtifactsInMillis = new AtomicLong(0);
  }

  private void unregister() {
    buckEventBus.unregister(this);
  }

  /**
   * Listens for {@link BuildRuleEvent.Finished} and adds to {@link
   * CacheMissBuildTimeListener#timeBuildingArtifactsInMillis} how long it took to build the
   * artifact if it could not be fetched from the cache and prints a warning if the sum goes above a
   * given threshold.
   *
   * @param finished contains the data associated with the build of the given rule.
   */
  @Subscribe
  public void onBuildRuleFinished(BuildRuleEvent.Finished finished) {
    if (finished.getCacheResult().getType() == CacheResultType.MISS) {
      finished
          .getBuildTimestamps()
          .ifPresent(
              buildTimestamps ->
                  timeBuildingArtifactsInMillis.getAndAdd(
                      buildTimestamps.getSecond() - buildTimestamps.getFirst()));
      if (timeBuildingArtifactsInMillis.get()
          > TimeUnit.SECONDS.toMillis(config.getDivergenceWarningThresholdInSec())) {
        buckEventBus.post(
            ConsoleEvent.warning(
                String.format("%s%n%n", config.getDivergenceWarningMessage().get())));
        unregister();
      }
    }
  }
}
