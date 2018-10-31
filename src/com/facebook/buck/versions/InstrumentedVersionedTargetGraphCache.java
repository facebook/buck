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

package com.facebook.buck.versions;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.cache.CacheStats;
import com.facebook.buck.util.cache.CacheStatsTracker;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;

/**
 * Wrapper class around VersionedTargetGraphCache containing a command specific stats tracker to
 * track performance of the cache
 */
public class InstrumentedVersionedTargetGraphCache {

  private final CacheStatsTracker statsTracker;
  private final VersionedTargetGraphCache cache;

  public InstrumentedVersionedTargetGraphCache(
      VersionedTargetGraphCache cache, CacheStatsTracker statsTracker) {
    this.statsTracker = statsTracker;
    this.cache = cache;
  }

  /**
   * @return a versioned target graph, either generated from the parameters or retrieved from a
   *     cache, with the current CacheStatsTracker.
   */
  public VersionedTargetGraphCacheResult getVersionedTargetGraph(
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      ImmutableMap<String, VersionUniverse> versionUniverses,
      ForkJoinPool pool)
      throws VersionException, InterruptedException, TimeoutException {
    return cache.toVersionedTargetGraph(
        eventBus,
        versionUniverses,
        typeCoercerFactory,
        targetGraphAndBuildTargets,
        pool,
        statsTracker);
  }

  /**
   * @return a versioned target graph, either generated from the parameters or retrieved from a
   *     cache, with the current CacheStatsTracker
   */
  public TargetGraphAndBuildTargets toVersionedTargetGraph(
      BuckEventBus eventBus,
      BuckConfig buckConfig,
      TypeCoercerFactory typeCoercerFactory,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets)
      throws VersionException, InterruptedException {
    return cache
        .getVersionedTargetGraph(
            eventBus,
            typeCoercerFactory,
            targetGraphAndBuildTargets,
            new VersionBuckConfig(buckConfig).getVersionUniverses(),
            new ForkJoinPool(buckConfig.getNumThreads()),
            new VersionBuckConfig(buckConfig),
            statsTracker)
        .getTargetGraphAndBuildTargets();
  }

  /** @return a CacheStats object containing the performance data of this cache */
  public CacheStats getCacheStats() {
    CacheStats.Builder statsBuilder =
        CacheStats.builder()
            .setHitCount(statsTracker.getTotalHitCount())
            .setMissCount(statsTracker.getTotalMissCount())
            .setMissMatchCount(statsTracker.getTotalMissMatchCount());

    if (statsTracker.getTotalHitCount()
            + statsTracker.getTotalMissCount()
            + statsTracker.getTotalMissMatchCount()
        > 0) {
      statsBuilder
          .setRetrievalTime(statsTracker.getAverageRetrievalTime())
          .setTotalMissTime(statsTracker.getAverageMissTime())
          .setTotalLoadTime(statsTracker.getAverageLoadTime());
    }

    return statsBuilder.build();
  }
}
