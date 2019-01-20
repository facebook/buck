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

package com.facebook.buck.util.perf;

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.FakeInvocationInfoFactory;
import com.google.common.eventbus.Subscribe;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PerfStatsTrackingTest {
  @Test
  public void probingMemoryPostsToTheEventBus() throws Exception {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    BlockingQueue<BuckEvent> events = new LinkedBlockingQueue<>();
    eventBus.register(
        new Object() {
          @Subscribe
          public void event(BuckEvent event) {
            events.add(event);
          }
        });
    try (PerfStatsTrackingForTest perfStatsTracking =
        new PerfStatsTrackingForTest(eventBus, FakeInvocationInfoFactory.create())) {

      perfStatsTracking.runOneIteration();
      // The BuckEventBus runs on a separate thread, give it a moment to push the event.
      BuckEvent event = events.poll(100, TimeUnit.MILLISECONDS);
      assertThat(event, Matchers.notNullValue());
      assertThat(event, Matchers.instanceOf(PerfStatsTracking.MemoryPerfStatsEvent.class));
      PerfStatsTracking.MemoryPerfStatsEvent memoryEvent =
          (PerfStatsTracking.MemoryPerfStatsEvent) event;
      assertThat(memoryEvent.getTotalMemoryBytes(), Matchers.greaterThan(0L));
      assertThat(
          memoryEvent.getMaxMemoryBytes(),
          Matchers.greaterThanOrEqualTo(memoryEvent.getTotalMemoryBytes()));
      assertThat(memoryEvent.getCurrentMemoryBytesUsageByPool().size(), Matchers.greaterThan(0));
    }
  }

  private static class PerfStatsTrackingForTest extends PerfStatsTracking {
    public PerfStatsTrackingForTest(BuckEventBus eventBus, InvocationInfo invocationInfo) {
      super(eventBus, invocationInfo);
    }

    @Override
    public void runOneIteration() throws Exception {
      super.runOneIteration();
    }
  }
}
