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

import static org.junit.Assert.assertFalse;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.google.common.eventbus.Subscribe;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GCNotificationEventEmitterTest {
  @Test
  public void smokeTestInducedCollection() throws InterruptedException {
    BuckEventBus bus = BuckEventBusForTests.newInstance();
    MockGCListener listener = new MockGCListener();
    GCNotificationEventEmitter.register(bus);
    bus.register(listener);
    System.gc();

    // GCs themselves are asynchronous and often happen in the background - give it a chance to
    // finish and dispatch its event.
    Thread.sleep(200);
    bus.waitEvents(200);
    assertFalse(listener.getGcEvents().isEmpty());
  }

  public static class MockGCListener {
    private final List<GCCollectionEvent> gcEvents = new ArrayList<>();

    @Subscribe
    public void onGcEvent(GCCollectionEvent gc) {
      gcEvents.add(gc);
    }

    public List<GCCollectionEvent> getGcEvents() {
      return gcEvents;
    }
  }
}
