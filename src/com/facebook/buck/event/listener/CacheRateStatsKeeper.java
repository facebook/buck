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
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.external.events.CacheRateStatsUpdateExternalEventInterface;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableCollection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measure CACHE HIT % based on total cache misses and the theoretical total number of build rules.
 * REASON: If we only look at cache_misses and processed rules we are strongly biasing the result
 * toward misses. Basically misses weight more than hits. One MISS will traverse all its dependency
 * subtree potentially generating misses for all internal Nodes; worst case generating N
 * cache_misses. One HIT will prevent any further traversal of dependency sub-tree nodes so it will
 * only count as one cache_hit even though it saved us from fetching N nodes.
 */
public class CacheRateStatsKeeper {
  // Counts the rules that have updated rule keys.
  private final AtomicInteger updated = new AtomicInteger(0);

  // Counts the number of cache misses and errors, respectively.
  private final AtomicInteger cacheMisses = new AtomicInteger(0);
  private final AtomicInteger cacheErrors = new AtomicInteger(0);
  private final AtomicInteger cacheHits = new AtomicInteger(0);
  private final AtomicInteger cacheIgnores = new AtomicInteger(0);
  private final AtomicInteger cacheLocalKeyUnchangedHits = new AtomicInteger(0);
  private final AtomicInteger unexpectedCacheMissesInStampedeBuildSlave = new AtomicInteger(0);

  protected final AtomicInteger ruleCount = new AtomicInteger(0);

  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (finished.getStatus() == BuildRuleStatus.CANCELED) {
      return;
    }
    CacheResult cacheResult = finished.getCacheResult();
    switch (cacheResult.getType()) {
      case MISS:
        cacheMisses.incrementAndGet();
        break;
      case ERROR:
        cacheErrors.incrementAndGet();
        break;
      case HIT:
        cacheHits.incrementAndGet();
        break;
      case IGNORED:
        cacheIgnores.incrementAndGet();
        break;
      case CONTAINS:
      case SKIPPED:
        throw new IllegalStateException(
            String.format(
                "BuildRules shouldn't finish with %s cache result type.",
                cacheResult.getType().toString()));
      case LOCAL_KEY_UNCHANGED_HIT:
        cacheLocalKeyUnchangedHits.incrementAndGet();
        break;
    }
    if (cacheResult.getType() != CacheResultType.LOCAL_KEY_UNCHANGED_HIT) {
      updated.incrementAndGet();
    }
  }

  public void recordUnexpectedCacheMisses(int unexpectedMisses) {
    unexpectedCacheMissesInStampedeBuildSlave.addAndGet(unexpectedMisses);
  }

  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount.set(calculated.getNumRules());
  }

  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    ruleCount.set(updated.getNumRules());
  }

  public CacheRateStats getSerializableStats() {
    CacheRateStats serializableStats = new CacheRateStats();
    serializableStats.setTotalRulesCount(ruleCount.get());
    serializableStats.setUpdatedRulesCount(updated.get());
    serializableStats.setCacheHitsCount(cacheHits.get());
    serializableStats.setCacheMissesCount(cacheMisses.get());
    serializableStats.setCacheErrorsCount(cacheErrors.get());
    serializableStats.setCacheLocalKeyUnchangedHitsCount(cacheLocalKeyUnchangedHits.get());
    serializableStats.setCacheIgnoresCount(cacheIgnores.get());
    serializableStats.setCacheIgnoresCount(cacheIgnores.get());
    serializableStats.setUnexpectedCacheMissesCount(
        unexpectedCacheMissesInStampedeBuildSlave.get());

    return serializableStats;
  }

  public static CacheRateStatsUpdateEvent getCacheRateStatsUpdateEventFromSerializedStats(
      CacheRateStats serializedStats) {
    return new CacheRateStatsUpdateEvent(
        serializedStats.getCacheMissesCount(),
        serializedStats.getCacheErrorsCount(),
        serializedStats.getCacheHitsCount(),
        serializedStats.getTotalRulesCount(),
        serializedStats.getUpdatedRulesCount());
  }

  public static CacheRateStatsUpdateEvent getAggregatedCacheRateStats(
      ImmutableCollection<CacheRateStatsUpdateEvent> statsEvents) {
    int cacheMisses = 0;
    int cacheErrors = 0;
    int cacheHits = 0;
    int totalRuleCount = 0;
    int updatedRuleCount = 0;

    for (CacheRateStatsUpdateEvent stats : statsEvents) {
      cacheMisses += stats.getCacheMissCount();
      cacheErrors += stats.getCacheErrorCount();
      cacheHits += stats.getCacheHitCount();
      totalRuleCount += stats.getTotalRulesCount();
      updatedRuleCount += stats.getUpdatedRulesCount();
    }

    return new CacheRateStatsUpdateEvent(
        cacheMisses, cacheErrors, cacheHits, totalRuleCount, updatedRuleCount);
  }

  public CacheRateStatsUpdateEvent getStats() {
    return new CacheRateStatsUpdateEvent(
        cacheMisses.get(), cacheErrors.get(), cacheHits.get(), ruleCount.get(), updated.get());
  }

  public static class CacheRateStatsUpdateEvent extends AbstractBuckEvent
      implements CacheRateStatsUpdateExternalEventInterface {

    private final int cacheMissCount;
    private final int cacheErrorCount;
    private final int cacheHitCount;
    private final int ruleCount;
    private final int updated;

    public CacheRateStatsUpdateEvent(
        int cacheMissCount, int cacheErrorCount, int cacheHitCount, int ruleCount, int updated) {
      super(EventKey.unique());
      this.cacheMissCount = cacheMissCount;
      this.cacheErrorCount = cacheErrorCount;
      this.cacheHitCount = cacheHitCount;
      this.ruleCount = ruleCount;
      this.updated = updated;
    }

    @Override
    protected String getValueString() {
      return MoreObjects.toStringHelper("")
          .add("ruleCount", ruleCount)
          .add("updated", updated)
          .add("cacheMissCount", cacheMissCount)
          .add("cacheMissRate", getCacheMissRate())
          .add("cacheErrorCount", cacheErrorCount)
          .add("cacheErrorRate", getCacheErrorRate())
          .add("cacheHitCount", cacheHitCount)
          .toString();
    }

    @Override
    public int getCacheMissCount() {
      return cacheMissCount;
    }

    @Override
    public int getCacheErrorCount() {
      return cacheErrorCount;
    }

    @Override
    public double getCacheMissRate() {
      return ruleCount == 0 ? 0 : 100 * (double) cacheMissCount / ruleCount;
    }

    @Override
    public double getCacheErrorRate() {
      return updated == 0 ? 0 : 100 * (double) cacheErrorCount / updated;
    }

    @Override
    public int getCacheHitCount() {
      return cacheHitCount;
    }

    @Override
    public int getUpdatedRulesCount() {
      return updated;
    }

    public int getTotalRulesCount() {
      return ruleCount;
    }

    @Override
    public String getEventName() {
      return CacheRateStatsUpdateExternalEventInterface.EVENT_NAME;
    }
  }
}
