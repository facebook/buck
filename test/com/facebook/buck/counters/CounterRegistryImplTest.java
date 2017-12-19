/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.counters;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.FakeExecutor;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CounterRegistryImplTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS =
      ImmutableMap.of("My super Tag Key", "And the according value!");

  private BuckEventBus eventBus;
  private ScheduledThreadPoolExecutor executor;
  private Capture<Runnable> flushCountersRunnable;
  private Capture<BuckEvent> countersEvent;

  // Apparently EasyMock does not deal very well with Generic Types using wildcard ?.
  // Several workarounds can be found on StackOverflow this one being the list intrusive.
  @SuppressWarnings("rawtypes")
  private ScheduledFuture mockFuture;

  private static class SnapshotEventListener {
    public final List<CountersSnapshotEvent> snapshotEvents =
        new ArrayList<CountersSnapshotEvent>();

    @Subscribe
    public void onCountersSnapshotEvent(CountersSnapshotEvent event) {
      snapshotEvents.add(event);
    }
  }

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    this.countersEvent = EasyMock.newCapture();
    this.mockFuture = EasyMock.createMock(ScheduledFuture.class);
    this.flushCountersRunnable = EasyMock.newCapture();
    this.eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    this.executor = EasyMock.createNiceMock(ScheduledThreadPoolExecutor.class);

    this.eventBus.post(EasyMock.capture(countersEvent));
    EasyMock.expectLastCall();
    EasyMock.expect(
            this.executor.scheduleAtFixedRate(
                EasyMock.capture(flushCountersRunnable),
                EasyMock.anyInt(),
                EasyMock.anyInt(),
                EasyMock.anyObject(TimeUnit.class)))
        .andReturn(this.mockFuture);
    EasyMock.expect(this.mockFuture.cancel(EasyMock.anyBoolean())).andReturn(true).once();

    EasyMock.replay(eventBus, executor, mockFuture);
  }

  @Test
  public void testCreatingCounters() throws IOException {
    try (CounterRegistryImpl registry = new CounterRegistryImpl(executor, eventBus)) {
      Assert.assertNotNull(registry.newIntegerCounter(CATEGORY, "counter1", TAGS));
      Assert.assertNotNull(registry.newSamplingCounter(CATEGORY, "counter2", TAGS));
      Assert.assertNotNull(registry.newTagSetCounter(CATEGORY, "counter3", TAGS));
    }
  }

  @Test
  public void testFlushingCounters() throws IOException {
    try (CounterRegistryImpl registry = new CounterRegistryImpl(executor, eventBus)) {
      IntegerCounter counter = registry.newIntegerCounter(CATEGORY, NAME, TAGS);
      counter.inc(42);
      TagSetCounter tagSetCounter = registry.newTagSetCounter(CATEGORY, "TagSet_Counter", TAGS);
      tagSetCounter.add("value1");
      tagSetCounter.addAll(ImmutableSet.of("value2", "value3"));
      Assert.assertTrue(flushCountersRunnable.hasCaptured());
      flushCountersRunnable.getValue().run();
      Assert.assertTrue(countersEvent.hasCaptured());
      CountersSnapshotEvent event = (CountersSnapshotEvent) countersEvent.getValue();
      Assert.assertEquals(2, event.getSnapshots().size());
      Assert.assertEquals(42, (long) event.getSnapshots().get(0).getValues().values().toArray()[0]);
      Assert.assertEquals(
          ImmutableSetMultimap.of(
              "TagSet_Counter", "value1",
              "TagSet_Counter", "value2",
              "TagSet_Counter", "value3"),
          event.getSnapshots().get(1).getTagSets());
    }
  }

  @Test
  public void noEventsFlushedIfNoCountersRegistered() throws IOException {
    BuckEventBus fakeEventBus =
        new DefaultBuckEventBus(FakeClock.doNotCare(), false, new BuildId("12345"), 1000);
    SnapshotEventListener listener = new SnapshotEventListener();
    fakeEventBus.register(listener);
    FakeExecutor fakeExecutor = new FakeExecutor();
    try (CounterRegistryImpl registry = new CounterRegistryImpl(fakeExecutor, fakeEventBus)) {
      assertThat(
          "No events should be flushed before timer fires", listener.snapshotEvents, empty());
      fakeExecutor.drain();
      assertThat("No events should be flushed after timer fires", listener.snapshotEvents, empty());
    }

    assertThat(
        "No events should be flushed after registry closed", listener.snapshotEvents, empty());
  }

  @Test
  public void noEventsFlushedIfCounterRegisteredButHasNoData() throws IOException {
    BuckEventBus fakeEventBus =
        new DefaultBuckEventBus(FakeClock.doNotCare(), false, new BuildId("12345"), 1000);
    SnapshotEventListener listener = new SnapshotEventListener();
    fakeEventBus.register(listener);
    FakeExecutor fakeExecutor = new FakeExecutor();
    try (CounterRegistryImpl registry = new CounterRegistryImpl(fakeExecutor, fakeEventBus)) {
      registry.newIntegerCounter(CATEGORY, NAME, TAGS);
      assertThat(
          "No events should be flushed before timer fires", listener.snapshotEvents, empty());
      fakeExecutor.drain();
      assertThat(
          "No events should be flushed after timer fires when counter has no data",
          listener.snapshotEvents,
          empty());
    }

    assertThat(
        "No events should be flushed after registry closed", listener.snapshotEvents, empty());
  }

  @Test
  public void eventIsFlushedIfCounterRegisteredWithData() throws IOException {
    BuckEventBus fakeEventBus =
        new DefaultBuckEventBus(FakeClock.doNotCare(), false, new BuildId("12345"), 1000);
    SnapshotEventListener listener = new SnapshotEventListener();
    fakeEventBus.register(listener);
    FakeExecutor fakeExecutor = new FakeExecutor();

    try (CounterRegistryImpl registry = new CounterRegistryImpl(fakeExecutor, fakeEventBus)) {
      IntegerCounter counter = registry.newIntegerCounter(CATEGORY, NAME, TAGS);
      counter.inc(42);
      assertThat(
          "No events should be flushed before timer fires", listener.snapshotEvents, empty());
      fakeExecutor.drain();
      assertThat(
          "One event should be flushed after timer fires", listener.snapshotEvents, hasSize(1));
      assertThat(
          "Counter with expected value should be flushed after timer fires",
          listener.snapshotEvents.get(0).getSnapshots(),
          hasItem(
              CounterSnapshot.builder()
                  .setCategory(CATEGORY)
                  .setTags(TAGS)
                  .putValues(NAME, 42)
                  .build()));
    }
  }

  @Test
  public void closingRegistryBeforeTimerFiresFlushesCounters() throws IOException {
    BuckEventBus fakeEventBus =
        new DefaultBuckEventBus(FakeClock.doNotCare(), false, new BuildId("12345"), 1000);
    SnapshotEventListener listener = new SnapshotEventListener();
    fakeEventBus.register(listener);
    FakeExecutor fakeExecutor = new FakeExecutor();

    try (CounterRegistryImpl registry = new CounterRegistryImpl(fakeExecutor, fakeEventBus)) {
      IntegerCounter counter = registry.newIntegerCounter(CATEGORY, NAME, TAGS);
      counter.inc(42);
      assertThat(
          "No events should be flushed before timer fires", listener.snapshotEvents, empty());
    }

    // We explicitly do not call fakeExecutor.drain() here, because we want to simulate what
    // happens when the registry is closed before the executor fires.

    assertThat(
        "One snapshot event should be flushed when registry closed before timer fires",
        listener.snapshotEvents,
        hasSize(1));
    assertThat(
        "Expected snapshot should be flushed when registry closed before timer fires",
        listener.snapshotEvents.get(0).getSnapshots(),
        hasItem(
            CounterSnapshot.builder()
                .setCategory(CATEGORY)
                .setTags(TAGS)
                .putValues(NAME, 42)
                .build()));
  }
}
