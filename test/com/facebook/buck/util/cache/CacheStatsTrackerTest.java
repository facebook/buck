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

import com.facebook.buck.util.timing.SettableFakeClock;
import org.junit.Before;
import org.junit.Test;

public class CacheStatsTrackerTest {

  private SettableFakeClock clock;
  private CacheStatsTracker tracker;

  @Before
  public void setUp() {
    clock = new SettableFakeClock(0, 999999999);
    tracker = new InstrumentingCacheStatsTracker(clock);
  }

  @Test
  public void testRecordHit() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    clock.setCurrentTimeMillis(2);
    request.recordHit();
    assertEquals((Long) 1L, tracker.getTotalHitCount());
    assertEquals((Long) 0L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 2L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 0L, tracker.getTotalMissTime());
    assertEquals((Long) 2L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 0L, tracker.getAverageMissTime());

    request = tracker.startRequest();
    clock.setCurrentTimeMillis(5);
    request.recordHit();
    assertEquals((Long) 2L, tracker.getTotalHitCount());
    assertEquals((Long) 0L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 5L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 0L, tracker.getTotalMissTime());
    assertEquals((Long) (5L / 2L), tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 0L, tracker.getAverageMissTime());
  }

  @Test
  public void testRecordMiss() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    clock.setCurrentTimeMillis(5);
    request.recordMiss();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 1L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 5L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 5L, tracker.getAverageMissTime());

    request = tracker.startRequest();
    clock.setCurrentTimeMillis(6);
    request.recordMiss();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 2L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 6L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) (6L / 2L), tracker.getAverageMissTime());
  }

  @Test
  public void testRecordMissMatch() {
    clock.setCurrentTimeMillis(2);
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    clock.setCurrentTimeMillis(5);
    request.recordMissMatch();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 0L, tracker.getTotalMissCount());
    assertEquals((Long) 1L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 3L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 3L, tracker.getAverageMissTime());

    request = tracker.startRequest();
    clock.setCurrentTimeMillis(6);
    request.recordMissMatch();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 0L, tracker.getTotalMissCount());
    assertEquals((Long) 2L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 4L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) (4L / 2L), tracker.getAverageMissTime());
  }

  @Test
  public void testRecordLoadSuccess() {
    clock.setCurrentTimeMillis(2);
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    clock.setCurrentTimeMillis(5);
    request.recordMiss();
    clock.setCurrentTimeMillis(6);
    request.recordLoadSuccess();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 1L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 1L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 1L, tracker.getTotalLoadTime());
    assertEquals((Long) 4L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 1L, tracker.getAverageLoadTime());
    assertEquals((Long) 4L, tracker.getAverageMissTime());

    request = tracker.startRequest();
    clock.setCurrentTimeMillis(7);
    request.recordMissMatch();
    clock.setCurrentTimeMillis(9);
    request.recordLoadSuccess();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 1L, tracker.getTotalMissCount());
    assertEquals((Long) 1L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 2L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 3L, tracker.getTotalLoadTime());
    assertEquals((Long) 7L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) (3L / 2L), tracker.getAverageLoadTime());
    assertEquals((Long) (7L / 2L), tracker.getAverageMissTime());
  }

  @Test
  public void testRecordLoadFail() {
    clock.setCurrentTimeMillis(2);
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    clock.setCurrentTimeMillis(5);
    request.recordMiss();
    clock.setCurrentTimeMillis(6);
    request.recordLoadFail();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 1L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 1L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 4L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 4L, tracker.getAverageMissTime());

    request = tracker.startRequest();
    clock.setCurrentTimeMillis(7);
    request.recordMissMatch();
    clock.setCurrentTimeMillis(9);
    request.recordLoadFail();
    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 1L, tracker.getTotalMissCount());
    assertEquals((Long) 1L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 2L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 7L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) (7L / 2L), tracker.getAverageMissTime());
  }

  @Test
  public void testNewInstanceReset() {
    tracker.startRequest().recordHit();
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    tracker = new InstrumentingCacheStatsTracker(clock);

    // the old request should have no effect on new tracker
    request.recordMiss();

    assertEquals((Long) 0L, tracker.getTotalHitCount());
    assertEquals((Long) 0L, tracker.getTotalMissCount());
    assertEquals((Long) 0L, tracker.getTotalMissMatchCount());
    assertEquals((Long) 0L, tracker.getTotalEvictionCount());
    assertEquals((Long) 0L, tracker.getTotalInvalidationCount());
    assertEquals((Long) 0L, tracker.getTotalLoadSuccessCount());
    assertEquals((Long) 0L, tracker.getTotalLoadExceptionCount());
    assertEquals((Long) 0L, tracker.getTotalRetrievalTime());
    assertEquals((Long) 0L, tracker.getTotalLoadTime());
    assertEquals((Long) 0L, tracker.getTotalMissTime());
    assertEquals((Long) 0L, tracker.getAverageRetrievalTime());
    assertEquals((Long) 0L, tracker.getAverageLoadTime());
    assertEquals((Long) 0L, tracker.getAverageMissTime());
  }

  @Test
  public void testEvictionAndInvalidation() {
    tracker.recordInvalidation();
    assertEquals((Long) 1L, tracker.getTotalInvalidationCount());
    tracker.recordInvalidation(4);
    assertEquals((Long) 5L, tracker.getTotalInvalidationCount());

    tracker.recordEviction(7);
    assertEquals((Long) 7L, tracker.getTotalEvictionCount());
    tracker.recordEviction();
    assertEquals((Long) 8L, tracker.getTotalEvictionCount());
  }

  @Test(expected = IllegalStateException.class)
  public void testDuplicateHitOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordHit();
    request.recordHit();
  }

  @Test(expected = IllegalStateException.class)
  public void testDuplicateMissOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMiss();
    request.recordMiss();
  }

  @Test(expected = IllegalStateException.class)
  public void testDuplicateMissMatchOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMissMatch();
    request.recordMissMatch();
  }

  @Test(expected = IllegalStateException.class)
  public void testMissAndMissMatchOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMiss();
    request.recordMissMatch();
  }

  @Test(expected = IllegalStateException.class)
  public void testDuplicateLoadSuccessOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMiss();
    request.recordLoadSuccess();
    request.recordLoadSuccess();
  }

  @Test(expected = IllegalStateException.class)
  public void testDuplicateLoadFailOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMiss();
    request.recordLoadFail();
    request.recordLoadFail();
  }

  @Test(expected = IllegalStateException.class)
  public void testMissThenHitOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordMiss();
    request.recordHit();
  }

  @Test(expected = IllegalStateException.class)
  public void testHitThenMissOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordHit();
    request.recordMiss();
  }

  @Test(expected = IllegalStateException.class)
  public void testLoadWithoutMissOnRequestThrows() {
    CacheStatsTracker.CacheRequest request = tracker.startRequest();
    request.recordLoadFail();
  }
}
