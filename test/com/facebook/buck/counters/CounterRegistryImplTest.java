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

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableMap;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CounterRegistryImplTest {

  private static final String CATEGORY = "Counter_Category";
  private static final String NAME = "Counter_Name";
  public static final ImmutableMap<String, String> TAGS = ImmutableMap.of(
      "My super Tag Key", "And the according value!"
  );

  private BuckEventBus eventBus;
  private ScheduledThreadPoolExecutor executor;
  private Capture<Runnable> flushCountersRunnable;
  private Capture<BuckEvent> countersStartEvent;
  private Capture<BuckEvent> countersFinishEvent;

  // Apparently EasyMock does not deal very well with Generic Types using wildcard ?.
  // Several workarounds can be found on StackOverflow this one being the list intrusive.
  @SuppressWarnings("rawtypes")
  private ScheduledFuture mockFuture;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    this.countersStartEvent = EasyMock.newCapture();
    this.countersFinishEvent = EasyMock.newCapture();
    this.mockFuture = EasyMock.createMock(ScheduledFuture.class);
    this.flushCountersRunnable = EasyMock.newCapture();
    this.eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    this.executor = EasyMock.createNiceMock(ScheduledThreadPoolExecutor.class);

    this.eventBus.post(EasyMock.capture(countersStartEvent));
    EasyMock.expectLastCall();
    this.eventBus.post(EasyMock.capture(countersFinishEvent));
    EasyMock.expectLastCall();
    EasyMock.expect(this.executor
        .scheduleAtFixedRate(
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
    }
  }

  @Test
  public void testFlushingCounters() throws IOException {
    try (CounterRegistryImpl registry = new CounterRegistryImpl(executor, eventBus)) {
      IntegerCounter counter = registry.newIntegerCounter(CATEGORY, NAME, TAGS);
      counter.inc(42);
      Assert.assertTrue(flushCountersRunnable.hasCaptured());
      flushCountersRunnable.getValue().run();
      Assert.assertTrue(countersFinishEvent.hasCaptured());
      CountersSnapshotEvent.Finished event =
          (CountersSnapshotEvent.Finished) countersFinishEvent.getValue();
      Assert.assertEquals(1, event.getSnapshots().size());
      Assert.assertEquals(42, (long) event.getSnapshots().get(0).getValues().values().toArray()[0]);
    }
  }
}
