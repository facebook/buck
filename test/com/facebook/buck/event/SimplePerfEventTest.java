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

package com.facebook.buck.event;

import static org.junit.Assert.assertThat;

import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SimplePerfEventTest {

  private void assertPerfEvent(
      BuckEvent event,
      PerfEventId id,
      SimplePerfEvent.Type type,
      ImmutableMap<String, String> info) {
    assertThat(event, Matchers.instanceOf(SimplePerfEvent.class));

    SimplePerfEvent perfEvent = (SimplePerfEvent) event;

    assertThat(perfEvent.getEventId(), Matchers.equalTo(id));
    assertThat(perfEvent.getEventType(), Matchers.equalTo(type));
    assertThat(
        Maps.transformValues(perfEvent.getEventInfo(), Object::toString), Matchers.equalTo(info));
  }

  @Test
  public void testManuallyCreatedStartEvents() {
    PerfEventId testEventId = PerfEventId.of("Test");

    assertPerfEvent(
        SimplePerfEvent.started(testEventId),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of());

    assertPerfEvent(
        SimplePerfEvent.started(testEventId, "k1", "v1"),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of("k1", "v1"));

    assertPerfEvent(
        SimplePerfEvent.started(testEventId, "k1", "v1", "k2", "v2"),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of("k1", "v1", "k2", "v2"));

    assertPerfEvent(
        SimplePerfEvent.started(testEventId, ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3")),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3"));
  }

  private SimplePerfEvent.Started newStartedEvent(PerfEventId testEventId) {
    return SimplePerfEvent.started(testEventId, "XX", "YY");
  }

  @Test
  public void testManuallyCreatedUpdateEvents() {
    PerfEventId testEventId = PerfEventId.of("Test");
    // Info from the started event does not get folded into the update/finished ones.

    assertPerfEvent(
        newStartedEvent(testEventId).createUpdateEvent(ImmutableMap.of()),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of());

    assertPerfEvent(
        newStartedEvent(testEventId).createUpdateEvent("k1", "v1"),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("k1", "v1"));

    assertPerfEvent(
        newStartedEvent(testEventId).createUpdateEvent("k1", "v1", "k2", "v2"),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("k1", "v1", "k2", "v2"));

    assertPerfEvent(
        newStartedEvent(testEventId)
            .createUpdateEvent(ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3")),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3"));
  }

  @Test
  public void testManuallyCreatedFinshedEvents() {
    PerfEventId testEventId = PerfEventId.of("Test");

    assertPerfEvent(
        newStartedEvent(testEventId).createFinishedEvent(ImmutableMap.of()),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of());

    assertPerfEvent(
        newStartedEvent(testEventId).createFinishedEvent("k1", "v1"),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("k1", "v1"));

    assertPerfEvent(
        newStartedEvent(testEventId).createFinishedEvent("k1", "v1", "k2", "v2"),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("k1", "v1", "k2", "v2"));

    assertPerfEvent(
        newStartedEvent(testEventId)
            .createFinishedEvent(ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3")),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3"));
  }

  @Test(expected = IllegalStateException.class)
  public void testThrowsOnDoubleFinish() {
    SimplePerfEvent.Started started = newStartedEvent(PerfEventId.of("test"));

    started.createFinishedEvent();
    started.createFinishedEvent();
  }

  private static class SimplePerfEventListener {
    private ImmutableList.Builder<SimplePerfEvent> perfEventBuilder = ImmutableList.builder();

    public ImmutableList<SimplePerfEvent> getPerfEvents() {
      return perfEventBuilder.build();
    }

    @Subscribe
    public void buckEvent(SimplePerfEvent perfEvent) {
      perfEventBuilder.add(perfEvent);
    }
  }

  @Test
  public void testScopedEvents() {
    PerfEventId testEventId = PerfEventId.of("Unicorn");

    SimplePerfEventListener listener = new SimplePerfEventListener();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(listener);

    // This does absolutely nothing, but shouldn't crash either.
    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(Optional.empty(), testEventId)) {
      scope.appendFinishedInfo("finished", "info");
      scope.update(ImmutableMap.of("update", "updateValue"));
    }

    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(eventBus, testEventId)) {
      scope.appendFinishedInfo("finished", "info");
      scope.update(ImmutableMap.of("update", "updateValue"));
      scope.update(ImmutableMap.of("update", "laterUpdate"));
    }

    ImmutableList<SimplePerfEvent> perfEvents = listener.getPerfEvents();
    assertThat(perfEvents, Matchers.hasSize(4));

    assertPerfEvent(
        perfEvents.get(0), testEventId, SimplePerfEvent.Type.STARTED, ImmutableMap.of());

    assertPerfEvent(
        perfEvents.get(1),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("update", "updateValue"));

    assertPerfEvent(
        perfEvents.get(2),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("update", "laterUpdate"));

    assertPerfEvent(
        perfEvents.get(3),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("finished", "info"));
  }

  @Test
  public void testMinimumTimeScope() {
    PerfEventId ignoredEventId = PerfEventId.of("IgnoreMe");
    PerfEventId loggedEventId = PerfEventId.of("LogMe");
    PerfEventId parentId = PerfEventId.of("Parent");

    SimplePerfEventListener listener = new SimplePerfEventListener();
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    BuckEventBus eventBus = BuckEventBusForTests.newInstance(clock);
    eventBus.register(listener);

    try (SimplePerfEvent.Scope parent = SimplePerfEvent.scope(eventBus, parentId)) {
      clock.advanceTimeNanos(10L);

      try (SimplePerfEvent.Scope scope =
          SimplePerfEvent.scopeIgnoringShortEvents(
              eventBus, ignoredEventId, parent, 1, TimeUnit.SECONDS)) {
        clock.advanceTimeNanos(10L);
      }

      clock.advanceTimeNanos(10L);

      try (SimplePerfEvent.Scope scope =
          SimplePerfEvent.scopeIgnoringShortEvents(
              eventBus, loggedEventId, parent, 1, TimeUnit.MILLISECONDS)) {
        clock.advanceTimeNanos(TimeUnit.MILLISECONDS.toNanos(2));
      }
    }

    ImmutableList<SimplePerfEvent> perfEvents = listener.getPerfEvents();
    assertThat(perfEvents, Matchers.hasSize(4));

    assertPerfEvent(perfEvents.get(0), parentId, SimplePerfEvent.Type.STARTED, ImmutableMap.of());

    assertPerfEvent(
        perfEvents.get(1), loggedEventId, SimplePerfEvent.Type.STARTED, ImmutableMap.of());

    assertPerfEvent(
        perfEvents.get(2), loggedEventId, SimplePerfEvent.Type.FINISHED, ImmutableMap.of());

    assertPerfEvent(
        perfEvents.get(3),
        parentId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of(
            "IgnoreMe_accumulated_count", "1",
            "IgnoreMe_accumulated_duration_ns", "10"));
  }
}
