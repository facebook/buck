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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.eventbus.Subscribe;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BroadcastEventListenerTest {

  private BlockingQueue<BuckEvent> trackedEvents = new LinkedBlockingQueue<>();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void tryBroadcastInMultipleBuses() {
    BuckEventBus bus1 =
        BuckEventBusFactory.newInstance(
            new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)), new BuildId("bus1"));
    BuckEventBus bus2 =
        BuckEventBusFactory.newInstance(
            new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)), new BuildId("bus2"));

    bus1.register(
        new Object() {
          @Subscribe
          public void actionGraphCacheMiss(ActionGraphEvent.Cache.Miss event) {
            trackedEvents.add(event);
          }
        });

    bus2.register(
        new Object() {
          @Subscribe
          public void actionGraphCacheHit(ActionGraphEvent.Cache.Hit event) {
            trackedEvents.add(event);
          }

          @Subscribe
          public void actionGraphCacheMiss(ActionGraphEvent.Cache.Miss event) {
            trackedEvents.add(event);
          }
        });

    BroadcastEventListener listener = new BroadcastEventListener();
    listener.addEventBus(bus1);
    listener.addEventBus(bus2);
    listener.broadcast(ActionGraphEvent.Cache.hit());
    listener.broadcast(ActionGraphEvent.Cache.miss(/* cacheWasEmpty */ false));

    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 2);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 1);
  }

  @Test
  public void throwWhenNoEventBusIsAvailable() throws RuntimeException {
    BroadcastEventListener listener = new BroadcastEventListener();
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("No available eventBus to broadcast event: WatchmanOverflow");
    listener.broadcast(WatchmanStatusEvent.overflow("and you know you're going to fall"));
  }

  private int countEventsOf(Class<? extends ActionGraphEvent> trackedClass) {
    int i = 0;
    for (BuckEvent event : trackedEvents) {
      if (trackedClass.isInstance(event)) {
        i++;
      }
    }
    return i;
  }
}
