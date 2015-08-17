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

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;

import org.hamcrest.Matchers;
import org.junit.Test;

public class SimplePerfEventTest {

  private void assertPerfEvent(BuckEvent event,
      PerfEventId id,
      SimplePerfEvent.Type type,
      ImmutableMap<String, String> info) {
    assertThat(event, Matchers.instanceOf(SimplePerfEvent.class));

    SimplePerfEvent perfEvent = (SimplePerfEvent) event;

    assertThat(perfEvent.getEventId(), Matchers.equalTo(id));
    assertThat(perfEvent.getEventType(), Matchers.equalTo(type));
    assertThat(
        perfEvent.getEventInfo(),
        Matchers.equalTo(Maps.transformValues(
                info, new Function<String, Object>() {
                  @Override
                  public Object apply(String input) {
                    return input;
                  }
                })));
  }

  @Test
  public void testManuallyCreatedStartEvents() {
    PerfEventId testEventId = PerfEventId.of("Test");

    assertPerfEvent(
        SimplePerfEvent.started(testEventId),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.<String, String>of());

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
        SimplePerfEvent.started(testEventId,
            ImmutableMap.<String, Object>of("k1", "v1", "k2", "v2", "k3", "v3")),
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
        newStartedEvent(testEventId).createUpdateEvent(ImmutableMap.<String, Object>of()),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.<String, String>of());

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
        newStartedEvent(testEventId).createUpdateEvent(
            ImmutableMap.<String, Object>of("k1", "v1", "k2", "v2", "k3", "v3")),
        testEventId,
        SimplePerfEvent.Type.UPDATED,
        ImmutableMap.of("k1", "v1", "k2", "v2", "k3", "v3"));
  }

  @Test
  public void testManuallyCreatedFinshedEvents() {
    PerfEventId testEventId = PerfEventId.of("Test");

    assertPerfEvent(
        newStartedEvent(testEventId).createFinishedEvent(ImmutableMap.<String, Object>of()),
        testEventId,
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.<String, String>of());

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
        newStartedEvent(testEventId).createFinishedEvent(
            ImmutableMap.<String, Object>of("k1", "v1", "k2", "v2", "k3", "v3")),
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
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    eventBus.register(listener);

    // This does absolutely nothing, but shouldn't crash either.
    try (SimplePerfEvent.Scope scope =
             SimplePerfEvent.scope(Optional.<BuckEventBus>absent(), testEventId)) {
      scope.appendFinishedInfo("finished", "info");
      scope.update(ImmutableMap.<String, Object>of("update", "updateValue"));
    }

    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(eventBus, testEventId)) {
      scope.appendFinishedInfo("finished", "info");
      scope.update(ImmutableMap.<String, Object>of("update", "updateValue"));
      scope.update(ImmutableMap.<String, Object>of("update", "laterUpdate"));
    }

    ImmutableList<SimplePerfEvent> perfEvents = listener.getPerfEvents();
    assertThat(perfEvents, Matchers.hasSize(4));

    assertPerfEvent(
        perfEvents.get(0),
        testEventId,
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.<String, String>of());

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
}
