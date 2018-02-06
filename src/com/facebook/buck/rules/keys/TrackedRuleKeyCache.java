/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.cache.CacheStats;
import com.facebook.buck.util.cache.CacheStatsTracker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A DefaultRuleKeyCache that records cache stats information in the corresponding CacheStatsTracker
 *
 * @param <V>
 */
public class TrackedRuleKeyCache<V> implements RuleKeyCache<V> {

  private final TrackableRuleKeyCache<V> cache;
  private final CacheStatsTracker statsTracker;

  public TrackedRuleKeyCache(TrackableRuleKeyCache<V> cache, CacheStatsTracker statsTracker) {
    this.cache = cache;
    this.statsTracker = statsTracker;
  }

  @Override
  @Nullable
  public V get(BuildRule rule) {
    return cache.get(rule, statsTracker);
  }

  @Override
  public V get(BuildRule rule, Function<? super BuildRule, RuleKeyResult<V>> create) {
    return cache.get(rule, create, statsTracker);
  }

  @Override
  public V get(AddsToRuleKey appendable, Function<? super AddsToRuleKey, RuleKeyResult<V>> create) {
    return cache.get(appendable, create, statsTracker);
  }

  @Override
  /** Invalidate the given inputs and all their transitive dependents. */
  public void invalidateInputs(Iterable<RuleKeyInput> inputs) {
    cache.invalidateInputs(inputs, statsTracker);
  }

  /**
   * Invalidate all inputs *not* from the given {@link ProjectFilesystem}s and their transitive
   * dependents.
   */
  @Override
  public void invalidateAllExceptFilesystems(ImmutableSet<ProjectFilesystem> filesystems) {
    cache.invalidateAllExceptFilesystems(filesystems, statsTracker);
  }

  /**
   * Invalidate all inputs from a given {@link ProjectFilesystem} and their transitive dependents.
   */
  @Override
  public void invalidateFilesystem(ProjectFilesystem filesystem) {
    cache.invalidateFilesystem(filesystem, statsTracker);
  }

  /** Invalidate everything in the cache. */
  @Override
  public void invalidateAll() {
    cache.invalidateAll(statsTracker);
  }

  @VisibleForTesting
  TrackableRuleKeyCache<V> getCache() {
    return cache;
  }

  /** @return the stats of the cache */
  public CacheStats getStats() {
    CacheStats.Builder statsBuilder =
        CacheStats.builder()
            .setHitCount(statsTracker.getTotalHitCount())
            .setMissCount(statsTracker.getTotalMissCount())
            .setEvictionCount(statsTracker.getTotalEvictionCount())
            .setRetrievalTime(statsTracker.getAverageRetrievalTime())
            .setTotalMissTime(statsTracker.getAverageMissTime())
            .setTotalLoadTime(statsTracker.getAverageLoadTime());

    return statsBuilder.build();
  }
}
