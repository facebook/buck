/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

/** Class containing various cache statistics */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCacheStats {
  // the number of hits on cache
  public abstract Optional<Long> getHitCount();
  // the number of misses on cache
  public abstract Optional<Long> getMissCount();
  // the number of misses on cache due to mismatch
  public abstract Optional<Long> getMissMatchCount();
  // the number of evictions
  public abstract Optional<Long> getEvictionCount();
  // the number of invalidations
  public abstract Optional<Long> getInvalidationCount();
  // the number of successful loads of objects into cache
  public abstract Optional<Long> getLoadSuccessCount();
  // the number of exceptions on loads of objects into cache
  public abstract Optional<Long> getLoadExceptionCount();
  // the time spend retrieving objects from cache
  public abstract Optional<Long> getRetrievalTime();
  // the time spent loading objects into cache
  public abstract Optional<Long> getTotalLoadTime();
  // the time spent total for a cache miss
  public abstract Optional<Long> getTotalMissTime();
  // the number of entries in cache at time of stat
  public abstract Optional<Long> getNumberEntries();

  /**
   * @return the total number of requests to the cash defined as {@code getHitCount() +
   *     getMissCount() + getMissMatchCount()} if both hitCount and one of missCount or
   *     missMissMatchCount is set
   */
  public Optional<Long> getRequestCount() {
    if (getHitCount().isPresent()
        && (getMissCount().isPresent() || getMissMatchCount().isPresent())) {
      return Optional.of(
          getHitCount().get() + getMissCount().orElse(0L) + getMissMatchCount().orElse(0L));
    }
    return Optional.empty();
  }

  /**
   * Returns the ratio of cache requests which were hits. This is defined as {@code getHitCount() /
   * requestCount}, or {@code 1.0} when {@code requestCount == 0}. Note that {@code hitRate +
   * missRate =~ 1.0}.
   */
  public Optional<Double> hitRate() {
    Optional<Long> requestCount = getRequestCount();
    if (requestCount.isPresent()) {
      return Optional.of(
          (requestCount.get() == 0) ? 1.0 : (double) getHitCount().get() / requestCount.get());
    }
    return Optional.empty();
  }

  /**
   * Returns the ratio of cache requests which were misses. This is defined as {@code getMissCount()
   * / requestCount}, or {@code 0.0} when {@code requestCount == 0}. Note that {@code hitRate +
   * missRate + missMatchRate =~ 1.0}. Cache misses include all requests which weren't cache hits,
   * including requests which resulted in either successful or failed loading attempts, and requests
   * which waited for other threads to finish loading. It is thus the case that {@code
   * getMissCount() + getMissMatchCount() &gt;= getLoadSuccessCount() + getLoadExceptionCount()}.
   * Multiple concurrent misses for the same key will result in a single load operation.
   */
  public Optional<Double> missRate() {
    Optional<Long> requestCount = getRequestCount();
    if (requestCount.isPresent() && getMissCount().isPresent()) {
      return Optional.of(
          (requestCount.get() == 0) ? 0.0 : (double) getMissCount().get() / requestCount.get());
    }
    return Optional.empty();
  }

  /**
   * Returns the ratio of cache requests which were misses due to mismatch. This is defined as
   * {@code getMissMatchCount() / requestCount}, or {@code 0.0} when {@code requestCount == 0}. Note
   * that {@code hitRate + missRate + missMatchRate =~ 1.0}. Cache misses include all requests which
   * weren't cache hits, including requests which resulted in either successful or failed loading
   * attempts, and requests which waited for other threads to finish loading. It is thus the case
   * that {@code getMissCount() + getMissMatchCount() &gt;= getLoadSuccessCount() +
   * getLoadExceptionCount()}. Multiple concurrent misses for the same key will result in a single
   * load operation.
   */
  public Optional<Double> missMatchRate() {
    Optional<Long> requestCount = getRequestCount();
    if (requestCount.isPresent() && getMissMatchCount().isPresent()) {
      return Optional.of(
          (requestCount.get() == 0)
              ? 0.0
              : (double) getMissMatchCount().get() / requestCount.get());
    }
    return Optional.empty();
  }

  /**
   * Adds or subtract two CacheStats if a field is specified by both CacheStats, with a minimum
   * value of 0. If only one CacheStats specifies the field, the unspecified value is treated as 0.
   * If non of the CacheStats specifies the field, the field will be empty.
   *
   * @param stats1 the stats before the arithmetic operator
   * @param stats2 the stats after the arithmetic operator
   * @return stats1 +/- stats2
   */
  public CacheStats subtract(CacheStats stats) {
    return aggregate(this, stats, false);
  }

  public CacheStats add(CacheStats stats) {
    return aggregate(this, stats, true);
  }

  private static CacheStats aggregate(
      AbstractCacheStats stats1, AbstractCacheStats stats2, boolean addOrMinus) {
    return CacheStats.builder()
        .setHitCount(aggregateFields(stats1.getHitCount(), stats2.getHitCount(), addOrMinus))
        .setMissCount(aggregateFields(stats1.getMissCount(), stats2.getMissCount(), addOrMinus))
        .setEvictionCount(
            aggregateFields(stats1.getEvictionCount(), stats2.getEvictionCount(), addOrMinus))
        .setInvalidationCount(
            aggregateFields(
                stats1.getInvalidationCount(), stats2.getInvalidationCount(), addOrMinus))
        .setLoadSuccessCount(
            aggregateFields(stats1.getLoadSuccessCount(), stats2.getLoadSuccessCount(), addOrMinus))
        .setLoadExceptionCount(
            aggregateFields(
                stats1.getLoadExceptionCount(), stats2.getLoadExceptionCount(), addOrMinus))
        .setTotalLoadTime(
            aggregateFields(stats1.getTotalLoadTime(), stats2.getTotalLoadTime(), addOrMinus))
        .setNumberEntries(
            aggregateFields(stats1.getNumberEntries(), stats2.getNumberEntries(), addOrMinus))
        .build();
  }

  private static Optional<Long> aggregateFields(
      Optional<Long> field1, Optional<Long> field2, boolean addOrMinus) {
    if (field1.isPresent() || field2.isPresent()) {
      long val1 = field1.orElse(0L);
      long val2 = field2.orElse(0L) * (addOrMinus ? 1 : -1);

      return Optional.of(Math.max(0, val1 + val2));
    }
    return Optional.empty();
  }
}
