/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.jvm;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class GCNotificationEventEmitterTest {

  private static final int MAX_WAIT_TIMEOUT_SECONDS = 1;

  @Rule public Timeout globalTestTimeout = Timeout.seconds(2 * MAX_WAIT_TIMEOUT_SECONDS);

  @Test
  public void smokeTestInducedCollection() throws InterruptedException {
    BuckEventBus bus = BuckEventBusForTests.newInstance();
    CountDownLatch countDownLatch = new CountDownLatch(1);
    MockGCListener listener = new MockGCListener(countDownLatch);
    GCNotificationEventEmitter.register(bus);
    bus.register(listener);

    // GCs themselves are asynchronous and often happen in the background - give it a chance to
    // finish and dispatch its event.
    System.gc();

    // wait for at least one event
    boolean ok = countDownLatch.await(MAX_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!ok) {
      Assert.fail("No gc events have been received");
    }

    GCCollectionEvent gcCollectionEvent = Iterables.getLast(listener.gcEvents);
    long duration = gcCollectionEvent.getDurationInMillis();
    assertTrue(duration > 0);
  }

  private static class MockGCListener {

    private final CountDownLatch countDownLatch;
    private final List<GCCollectionEvent> gcEvents;

    private MockGCListener(CountDownLatch countDownLatch) {
      this.countDownLatch = countDownLatch;
      this.gcEvents = new ArrayList<>();
    }

    @Subscribe
    public void onGcEvent(GCCollectionEvent gc) {
      countDownLatch.countDown();
      gcEvents.add(gc);
    }
  }
}
