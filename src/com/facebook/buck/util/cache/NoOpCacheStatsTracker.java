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
  public Long getTotalHitCount() {
    return 0L;
  }

  @Override
  public Long getTotalMissCount() {
    return 0L;
  }

  @Override
  public Long getTotalMissMatchCount() {
    return 0L;
  }

  @Override
  public Long getTotalEvictionCount() {
    return 0L;
  }

  @Override
  public Long getTotalInvalidationCount() {
    return 0L;
  }

  @Override
  public Long getTotalLoadSuccessCount() {
    return 0L;
  }

  @Override
  public Long getTotalLoadExceptionCount() {
    return 0L;
  }

  @Override
  public Long getTotalRetrievalTime() {
    return 0L;
  }

  @Override
  public Long getTotalLoadTime() {
    return 0L;
  }

  @Override
  public Long getTotalMissTime() {
    return 0L;
  }

  @Override
  public Long getAverageRetrievalTime() {
    return 0L;
  }

  @Override
  public Long getAverageMissTime() {
    return 0L;
  }

  @Override
  public Long getAverageLoadTime() {
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
