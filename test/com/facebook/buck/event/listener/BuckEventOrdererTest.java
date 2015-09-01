/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.event.TestEventConfigerator.configureTestEventAtTime;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;


public class BuckEventOrdererTest {

  private static final long THREAD_ONE = 1;
  private static final long THREAD_TWO = 2;
  private static final long MAX_SKEW = 10;
  private static final Function<BuckEvent, Long> TO_EVENT_TIMESTAMP_FUNCTION =
      new Function<BuckEvent, Long>() {
        @Override
        public Long apply(BuckEvent input) {
          return input.getTimestamp();
        }
      };

  private Deque<BuckEvent> serializedEvents = new ArrayDeque<>();
  private Function<BuckEvent, Void> addToSerializedEventsFunction =
      new Function<BuckEvent, Void>() {
        @Nullable
        @Override
        public Void apply(BuckEvent input) {
          serializedEvents.add(input);
          return null;
        }
      };

  @Test
  public void testMergesSingleSetOfSerialEvents() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(5, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(first);
      orderer.add(second);
    }

    assertThat(serializedEvents, Matchers.contains(first, second));
  }

  @Test
  public void testReordersEventsWithinSkewWindow() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(5, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(second);
      orderer.add(first);
    }

    assertThat(serializedEvents, Matchers.contains(first, second));
  }

  @Test
  public void testPreservesInsertionOrderForSameTimestamp() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent third = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(first);
      orderer.add(second);
      orderer.add(third);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third));
  }

  @Test
  public void testPreservesInsertionOrderForSameTimestampWithReorder() {
    BuckEvent first = createUniqueEvent(1, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent third = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent fourth = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(second);
      orderer.add(third);
      orderer.add(fourth);
      orderer.add(first);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third, fourth));
  }

  @Test
  public void testPreservesInsertionOrderForSameTimestampWithReverseReorder() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent third = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent fourth = createUniqueEvent(5, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(fourth);
      orderer.add(first);
      orderer.add(second);
      orderer.add(third);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third, fourth));
  }

  @Test
  public void testMergesTwoSetsOfSerialEvents() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(1, TimeUnit.MILLISECONDS, THREAD_TWO);
    BuckEvent third = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent fourth = createUniqueEvent(3, TimeUnit.MILLISECONDS, THREAD_TWO);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(second);
      orderer.add(fourth);
      orderer.add(first);
      orderer.add(third);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third, fourth));
  }

  @Test
  public void testMergesTwoInterleavedEventSeries() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(1, TimeUnit.MILLISECONDS, THREAD_TWO);
    BuckEvent third = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent fourth = createUniqueEvent(3, TimeUnit.MILLISECONDS, THREAD_TWO);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(second);
      orderer.add(first);
      orderer.add(fourth);
      orderer.add(third);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third, fourth));
  }

  @Test
  public void testMergesSingleEventAtStartCorrectly() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(1, TimeUnit.MILLISECONDS, THREAD_TWO);
    BuckEvent third = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(second);
      orderer.add(first);
      orderer.add(third);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third));
  }

  @Test
  public void testMergesSingleEventAtEndCorrectly() {
    BuckEvent first = createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE);
    BuckEvent second = createUniqueEvent(1, TimeUnit.MILLISECONDS, THREAD_TWO);
    BuckEvent third = createUniqueEvent(2, TimeUnit.MILLISECONDS, THREAD_ONE);

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(first);
      orderer.add(third);
      orderer.add(second);
    }

    assertThat(serializedEvents, Matchers.contains(first, second, third));
  }

  @Test
  public void testPutsEventInSinkAsSoonAsPossible() {

    try (BuckEventOrderer<BuckEvent> orderer = new BuckEventOrderer<>(
        addToSerializedEventsFunction, MAX_SKEW, TimeUnit.MILLISECONDS)) {
      orderer.add(createUniqueEvent(0, TimeUnit.MILLISECONDS, THREAD_ONE));
      orderer.add(createUniqueEvent(5, TimeUnit.MILLISECONDS, THREAD_ONE));
      orderer.add(createUniqueEvent(MAX_SKEW + 1, TimeUnit.MILLISECONDS, THREAD_TWO));

      assertThat(
          FluentIterable.from(serializedEvents).transform(TO_EVENT_TIMESTAMP_FUNCTION),
          Matchers.contains(0L));

      orderer.add(createUniqueEvent(5 + MAX_SKEW + 1, TimeUnit.MILLISECONDS, THREAD_ONE));

      assertThat(
          FluentIterable.from(serializedEvents).transform(TO_EVENT_TIMESTAMP_FUNCTION),
          Matchers.contains(0L, 5L));
    }

    assertThat(
        FluentIterable.from(serializedEvents).transform(TO_EVENT_TIMESTAMP_FUNCTION),
        Matchers.contains(0L, 5L, MAX_SKEW + 1, 5 + MAX_SKEW + 1));
  }

  private static int seqNo = 0;

  private BuckEvent createUniqueEvent(long timeInMs, TimeUnit timeUnit, long threadId) {
    return configureTestEventAtTime(
        (AbstractBuckEvent) SimplePerfEvent.started(
            PerfEventId.of("BuckEventOrdererTest"),
            ImmutableMap.<String, Object>of(
            "seqNo", seqNo++,
            "time", timeInMs,
            "thread", threadId)),
        timeInMs,
        timeUnit,
        threadId);
  }
}
