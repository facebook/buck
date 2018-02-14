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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Optional;
import org.junit.Test;

public class CacheStatsTest {

  @Test
  public void testOnlySetFieldsLog() {
    CacheStats stats = CacheStats.builder().setHitCount(1L).setNumberEntries(200L).build();

    assertEquals(Optional.of(1L), stats.getHitCount());
    assertEquals(Optional.empty(), stats.getMissCount());
    assertEquals(Optional.empty(), stats.getMissMatchCount());
    assertEquals(Optional.empty(), stats.getEvictionCount());
    assertEquals(Optional.empty(), stats.getInvalidationCount());
    assertEquals(Optional.empty(), stats.getLoadSuccessCount());
    assertEquals(Optional.empty(), stats.getLoadExceptionCount());
    assertEquals(Optional.empty(), stats.getRetrievalTime());
    assertEquals(Optional.empty(), stats.getTotalLoadTime());
    assertEquals(Optional.empty(), stats.getTotalMissTime());
    assertEquals(Optional.of(200L), stats.getNumberEntries());
  }

  @Test
  public void testCalculations() {
    CacheStats stats = CacheStats.builder().setHitCount(2L).setMissCount(4L).build();
    assertEquals(Optional.of(6L), stats.getRequestCount());
    assertEquals((double) 2 / 6, stats.hitRate().get(), 0.01);
    assertEquals((double) 4 / 6, stats.missRate().get(), 0.01);
    assertEquals(Optional.empty(), stats.missMatchRate());

    stats = CacheStats.builder().setHitCount(2L).setMissMatchCount(4L).build();
    assertEquals(Optional.of(6L), stats.getRequestCount());
    assertEquals((double) 2 / 6, stats.hitRate().get(), 0.01);
    assertEquals((double) 4 / 6, stats.missMatchRate().get(), 0.01);
    assertEquals(Optional.empty(), stats.missRate());

    stats = CacheStats.builder().setHitCount(2L).setMissMatchCount(3L).setMissCount(1L).build();
    assertEquals(Optional.of(6L), stats.getRequestCount());
  }

  @Test
  public void testCalculationsEmptyWhenFieldMissing() {
    CacheStats stats = CacheStats.builder().setMissCount(2L).build();
    assertFalse(stats.getRequestCount().isPresent());
    assertFalse(stats.hitRate().isPresent());
    assertFalse(stats.missRate().isPresent());
  }

  @Test
  public void testAddCacheStats() {
    CacheStats stats1 = CacheStats.builder().setHitCount(2L).setNumberEntries(500L).build();
    CacheStats stats2 = CacheStats.builder().setNumberEntries(100L).setMissCount(4L).build();

    CacheStats addStats = stats1.add(stats2);
    assertEquals(Optional.of(2L), addStats.getHitCount());
    assertEquals(Optional.of(4L), addStats.getMissCount());
    assertEquals(Optional.empty(), addStats.getMissMatchCount());
    assertEquals(Optional.empty(), addStats.getEvictionCount());
    assertEquals(Optional.empty(), addStats.getInvalidationCount());
    assertEquals(Optional.empty(), addStats.getLoadSuccessCount());
    assertEquals(Optional.empty(), addStats.getLoadExceptionCount());
    assertEquals(Optional.empty(), addStats.getRetrievalTime());
    assertEquals(Optional.empty(), addStats.getTotalLoadTime());
    assertEquals(Optional.empty(), addStats.getTotalMissTime());
    assertEquals(Optional.of(600L), addStats.getNumberEntries());
  }

  @Test
  public void testSubtractCacheStats() {
    CacheStats stats1 = CacheStats.builder().setHitCount(2L).setNumberEntries(500L).build();
    CacheStats stats2 = CacheStats.builder().setNumberEntries(100L).setMissCount(4L).build();

    CacheStats minusStats = stats1.subtract(stats2);
    assertEquals(Optional.of(2L), minusStats.getHitCount());
    assertEquals(Optional.of(0L), minusStats.getMissCount());
    assertEquals(Optional.empty(), minusStats.getMissMatchCount());
    assertEquals(Optional.empty(), minusStats.getEvictionCount());
    assertEquals(Optional.empty(), minusStats.getInvalidationCount());
    assertEquals(Optional.empty(), minusStats.getLoadSuccessCount());
    assertEquals(Optional.empty(), minusStats.getLoadExceptionCount());
    assertEquals(Optional.empty(), minusStats.getRetrievalTime());
    assertEquals(Optional.empty(), minusStats.getTotalLoadTime());
    assertEquals(Optional.empty(), minusStats.getTotalMissTime());
    assertEquals(Optional.of(400L), minusStats.getNumberEntries());
  }
}
