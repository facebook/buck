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

package com.facebook.buck.util.cache;

/** CacheStats tracker that doesn't track any stats */
public class NoOpCacheStatsTracker implements CacheStatsTracker {

  @Override
  public CacheRequest startRequest() {
    return new NoOpCacheRequest();
  }

  @Override
  public long getTotalHitCount() {
    return 0L;
  }

  @Override
  public long getTotalMissCount() {
    return 0L;
  }

  @Override
  public long getTotalMissMatchCount() {
    return 0L;
  }

  @Override
  public long getTotalEvictionCount() {
    return 0L;
  }

  @Override
  public long getTotalInvalidationCount() {
    return 0L;
  }

  @Override
  public long getTotalLoadSuccessCount() {
    return 0L;
  }

  @Override
  public long getTotalLoadExceptionCount() {
    return 0L;
  }

  @Override
  public long getTotalRetrievalTime() {
    return 0L;
  }

  @Override
  public long getTotalLoadTime() {
    return 0L;
  }

  @Override
  public long getTotalMissTime() {
    return 0L;
  }

  @Override
  public long getAverageRetrievalTime() {
    return 0L;
  }

  @Override
  public long getAverageMissTime() {
    return 0L;
  }

  @Override
  public long getAverageLoadTime() {
    return 0L;
  }

  @Override
  public void recordEviction() {}

  @Override
  public void recordEviction(long num) {}

  @Override
  public void recordInvalidation() {}

  @Override
  public void recordInvalidation(long num) {}

  /** CacheRequest that doesn't track stats */
  public class NoOpCacheRequest implements CacheStatsTracker.CacheRequest {

    @Override
    public void recordHit() {}

    @Override
    public void recordMiss() {}

    @Override
    public void recordMissMatch() {}

    @Override
    public void recordLoadSuccess() {}

    @Override
    public void recordLoadFail() {}
  }
}
