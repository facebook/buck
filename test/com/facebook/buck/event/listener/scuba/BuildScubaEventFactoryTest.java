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

package com.facebook.buck.event.listener.scuba;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.event.CacheStatsEvent;
import com.facebook.buck.event.ScubaData;
import com.facebook.buck.event.ScubaEvent;
import com.facebook.buck.util.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class BuildScubaEventFactoryTest {

  private ScubaListenerData scubaListenerData;

  @Before
  public void setUp() {
    scubaListenerData = new ScubaListenerData(ImmutableSet.of(), "");

    // fill in some fake basic info
    scubaListenerData.setLatestEventTimeMs(Optional.of(1L));
    scubaListenerData.setFirstEventTimeMs(Optional.of(0L));
  }

  @Test
  public void testCacheStatsEventLogOnlyFilledFields() {

    scubaListenerData.addCacheStatsEvents(
        new CacheStatsEvent(
            "test_cache1", CacheStats.builder().setHitCount(1L).setNumberEntries(200L).build()));

    scubaListenerData.addCacheStatsEvents(
        new CacheStatsEvent(
            "test_cache2", CacheStats.builder().setHitCount(4L).setMissCount(40L).build()));

    ImmutableList<ScubaEvent> scubaEvent =
        new BuildScubaEventFactory()
            .createScubaEvents(
                BuildData.of(
                    scubaListenerData,
                    ImmutableSet.of(),
                    ScubaData.builder().putInts("time", 11111111L).build(),
                    Optional.empty(),
                    Optional.empty(),
                    false));

    assertEquals(1, scubaEvent.size());
    ImmutableMap<String, Long> data = scubaEvent.get(0).getInts();
    assertEquals(
        (Long) 1L, data.get(toFieldName("test_cache1", BuildScubaEventFactory.HIT_COUNT_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.MISS_COUNT_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.MISS_MISMATCH_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.EVICTION_COUNT_FIELD)));
    assertNull(
        data.get(toFieldName("test_cache1", BuildScubaEventFactory.INVALIDATION_COUNT_FIELD)));
    assertNull(
        data.get(toFieldName("test_cache1", BuildScubaEventFactory.LOAD_SUCCESS_COUNT_FIELD)));
    assertNull(
        data.get(toFieldName("test_cache1", BuildScubaEventFactory.LOAD_EXCEPTION_COUNT_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.LOAD_TIME_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.RETRIEVAL_TIME_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.MISS_TIME_FIELD)));
    assertEquals(
        (Long) 200L,
        data.get(toFieldName("test_cache1", BuildScubaEventFactory.NUMBER_ENTRY_FIELD)));
    assertNull(data.get(toFieldName("test_cache1", BuildScubaEventFactory.REQUEST_COUNT_FIELD)));

    assertEquals((Long) 4L, data.get("test_cache2_hits"));
    assertEquals((Long) 40L, data.get("test_cache2_misses"));
    assertEquals((Long) 44L, data.get("test_cache2_requests"));
  }

  @Test
  public void testCacheStatsEventLogAllFields() {
    scubaListenerData.addCacheStatsEvents(
        new CacheStatsEvent(
            "test_cache",
            CacheStats.builder()
                .setHitCount(1L)
                .setMissCount(2L)
                .setMissMatchCount(3L)
                .setEvictionCount(4L)
                .setInvalidationCount(5L)
                .setLoadSuccessCount(6L)
                .setLoadExceptionCount(7L)
                .setTotalLoadTime(8L)
                .setRetrievalTime(9L)
                .setTotalMissTime(10L)
                .setNumberEntries(11L)
                .build()));

    ImmutableList<ScubaEvent> scubaEvent =
        new BuildScubaEventFactory()
            .createScubaEvents(
                BuildData.of(
                    scubaListenerData,
                    ImmutableSet.of(),
                    ScubaData.builder().putInts("time", 11111111L).build(),
                    Optional.empty(),
                    Optional.empty(),
                    false));

    assertEquals(1, scubaEvent.size());
    ImmutableMap<String, Long> data = scubaEvent.get(0).getInts();

    assertEquals(
        (Long) 1L, data.get(toFieldName("test_cache", BuildScubaEventFactory.HIT_COUNT_FIELD)));
    assertEquals(
        (Long) 2L, data.get(toFieldName("test_cache", BuildScubaEventFactory.MISS_COUNT_FIELD)));
    assertEquals(
        (Long) 3L, data.get(toFieldName("test_cache", BuildScubaEventFactory.MISS_MISMATCH_FIELD)));
    assertEquals(
        (Long) 4L,
        data.get(toFieldName("test_cache", BuildScubaEventFactory.EVICTION_COUNT_FIELD)));
    assertEquals(
        (Long) 5L,
        data.get(toFieldName("test_cache", BuildScubaEventFactory.INVALIDATION_COUNT_FIELD)));
    assertEquals(
        (Long) 6L,
        data.get(toFieldName("test_cache", BuildScubaEventFactory.LOAD_SUCCESS_COUNT_FIELD)));
    assertEquals(
        (Long) 7L,
        data.get(toFieldName("test_cache", BuildScubaEventFactory.LOAD_EXCEPTION_COUNT_FIELD)));
    assertEquals(
        (Long) 8L, data.get(toFieldName("test_cache", BuildScubaEventFactory.LOAD_TIME_FIELD)));
    assertEquals(
        (Long) 9L,
        data.get(toFieldName("test_cache", BuildScubaEventFactory.RETRIEVAL_TIME_FIELD)));
    assertEquals(
        (Long) 10L, data.get(toFieldName("test_cache", BuildScubaEventFactory.MISS_TIME_FIELD)));
    assertEquals(
        (Long) 11L, data.get(toFieldName("test_cache", BuildScubaEventFactory.NUMBER_ENTRY_FIELD)));
    assertEquals(
        (Long) 6L, data.get(toFieldName("test_cache", BuildScubaEventFactory.REQUEST_COUNT_FIELD)));
  }

  private static String toFieldName(String eventName, String field) {
    return String.format("%s_%s", eventName, field);
  }
}
