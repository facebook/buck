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

package com.facebook.buck.util.cache;

import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.LongAdder;

/** Class that tracks cache statistics, including timings. */
public final class CacheStatsTracker {

  private final Clock clock;

  private final LongAdder totalHitCount = new LongAdder();
  private final LongAdder totalMissCount = new LongAdder();
  private final LongAdder totalMissMatchCount = new LongAdder();
  private final LongAdder totalEvictionCount = new LongAdder();
  private final LongAdder totalInvalidationCount = new LongAdder();
  private final LongAdder totalLoadSuccessCount = new LongAdder();
  private final LongAdder totalLoadExceptionCount = new LongAdder();
  private final LongAdder totalRetrievalTime = new LongAdder();
  private final LongAdder totalLoadTime = new LongAdder();
  private final LongAdder totalMissTime = new LongAdder();

  public CacheStatsTracker() {
    this(new DefaultClock());
  }

  public CacheStatsTracker(Clock clock) {
    this.clock = clock;
  }

  /**
   * @return a CacheRequest object that will keep record of stats and timing for this request on the
   *     cache
   */
  public CacheRequest startRequest() {
    return new CacheRequest();
  }

  public Long getTotalHitCount() {
    return totalHitCount.longValue();
  }

  public Long getTotalMissCount() {
    return totalMissCount.longValue();
  }

  public Long getTotalMissMatchCount() {
    return totalMissMatchCount.longValue();
  }

  public Long getTotalEvictionCount() {
    return totalEvictionCount.longValue();
  }

  public Long getTotalInvalidationCount() {
    return totalInvalidationCount.longValue();
  }

  public Long getTotalLoadSuccessCount() {
    return totalLoadSuccessCount.longValue();
  }

  public Long getTotalLoadExceptionCount() {
    return totalLoadExceptionCount.longValue();
  }

  public Long getTotalRetrievalTime() {
    return totalRetrievalTime.longValue();
  }

  public Long getTotalLoadTime() {
    return totalLoadTime.longValue();
  }

  public Long getTotalMissTime() {
    return totalMissTime.longValue();
  }

  /**
   * @return the average retrieval time as defined by total time / total requests, or 0 if no
   *     requests have been made
   */
  public Long getAverageRetrievalTime() {
    long totalRequest =
        totalHitCount.longValue() + totalMissCount.longValue() + totalMissMatchCount.longValue();
    return totalRequest > 0 ? totalRetrievalTime.longValue() / totalRequest : 0;
  }

  /**
   * @return the average miss time as defined by total time / total requests, or 0 if no requests
   *     have been made
   */
  public Long getAverageMissTime() {
    long totalRequest =
        totalHitCount.longValue() + totalMissCount.longValue() + totalMissMatchCount.longValue();
    return totalRequest > 0 ? totalMissTime.longValue() / totalRequest : 0;
  }

  /**
   * @return the average load time as defined by total time / total requests, or 0 if no requests
   *     have been made
   */
  public Long getAverageLoadTime() {
    long totalRequest =
        totalHitCount.longValue() + totalMissCount.longValue() + totalMissMatchCount.longValue();
    return totalRequest > 0 ? totalLoadTime.longValue() / totalRequest : 0;
  }

  /** records a single eviction */
  public void recordEviction() {
    totalEvictionCount.increment();
  }

  /** @param num the number of evictions to record */
  public void recordEviction(long num) {
    totalEvictionCount.add(num);
  }

  /** records a single invalidation */
  public void recordInvalidation() {
    totalInvalidationCount.increment();
  }

  /** @param num the number of invalidations to record */
  public void recordInvalidation(long num) {
    totalInvalidationCount.add(num);
  }

  /** Class that keeps record and timings of a single cache request */
  public class CacheRequest {
    private long startTime = clock.currentTimeMillis();
    private long startLoadTime;
    private State state = State.INITIALIZED;

    private CacheRequest() {}

    /**
     * Records that a cache hit has occurred and updates the corresponding CacheStatsTracker, and
     * records the time it took for the cache retrieval
     */
    public void recordHit() {
      Preconditions.checkState(state == State.INITIALIZED);
      totalRetrievalTime.add(clock.currentTimeMillis() - startTime);
      totalHitCount.increment();
      state = State.HIT;
    }

    /**
     * Records that a cache miss has occurred and updates the corresponding CacheStatsTracker, and
     * starts recording the load time starting at this instant. If no load event occurs, the current
     * time will be recorded as the time it took for a cache miss
     */
    public void recordMiss() {
      Preconditions.checkState(state == State.INITIALIZED);
      startLoadTime = clock.currentTimeMillis();
      // in event that no load is performed, the miss time is as recorded here.
      totalMissTime.add(startLoadTime - startTime);
      totalMissCount.increment();
      state = State.MISS;
    }

    /**
     * Records that a cache miss due to mismatch has occurred and updates the corresponding
     * CacheStatsTracker, and starts recording the load time starting at this instant. If no load
     * event occurs, the current time will be recorded as the time it took for a cache miss
     */
    public void recordMissMatch() {
      Preconditions.checkState(state == State.INITIALIZED);
      startLoadTime = clock.currentTimeMillis();
      // in event that no load is performed, the miss time is as recorded here.
      totalMissTime.add(startLoadTime - startTime);
      totalMissMatchCount.increment();
      state = State.MISS;
    }

    /**
     * Records that a cache load was successful and updates the corresponding CacheStatsTracker, and
     * records the time it took to load the object and updates the total time spent on a cache miss
     */
    public void recordLoadSuccess() {
      Preconditions.checkState(state == State.MISS);
      long endTime = clock.currentTimeMillis();
      totalLoadTime.add(endTime - startLoadTime);
      // add the load time in addition to what we recorded in {@code recordMiss()} and {@code
      // recordMissMatch()} to get the total time spent when missed cache
      totalMissTime.add(endTime - startLoadTime);
      totalLoadSuccessCount.increment();
      state = State.LOADED;
    }

    /**
     * Records that a cache load has failed and updates the corresponding CacheStatsTracker, and
     * records the time spent on a cache miss
     */
    public void recordLoadFail() {
      Preconditions.checkState(state == State.MISS);
      long endTime = clock.currentTimeMillis();
      totalLoadExceptionCount.increment();
      // add the load time in addition to what we recorded in {@code recordMiss()} and {@code
      // recordMissMatch()} to get the total time spent when missed cache
      totalMissTime.add(endTime - startLoadTime);
      // since load failed, no load time is recorded
      state = State.LOADED;
    }
  }

  private enum State {
    INITIALIZED,
    HIT,
    MISS,
    LOADED,
  }
}
