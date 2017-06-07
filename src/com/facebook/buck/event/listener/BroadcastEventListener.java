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

import com.facebook.buck.event.BroadcastEvent;
import com.facebook.buck.event.BuckEventBus;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class BroadcastEventListener {

  @GuardedBy("this")
  private Set<BuckEventBus> eventBuses;

  public BroadcastEventListener() {
    eventBuses = new HashSet<>();
  }

  public synchronized void broadcast(BroadcastEvent event) {
    if (eventBuses.isEmpty()) {
      throw new RuntimeException(
          "No available eventBus to broadcast event: " + event.getEventName());
    }
    for (BuckEventBus eventBus : eventBuses) {
      if (event.isConfigured()) {
        eventBus.postWithoutConfiguring(event);
      } else {
        eventBus.post(event);
      }
    }
  }

  public synchronized BroadcastEventBusClosable addEventBus(BuckEventBus eventBus) {
    eventBuses.add(eventBus);
    return new BroadcastEventBusClosable(this, eventBus);
  }

  private synchronized void removeEventBus(BuckEventBus eventBus) {
    eventBuses.remove(eventBus);
  }

  public class BroadcastEventBusClosable implements AutoCloseable {
    BroadcastEventListener listener;
    BuckEventBus eventBus;

    public BroadcastEventBusClosable(BroadcastEventListener listener, BuckEventBus bus) {
      this.listener = listener;
      this.eventBus = bus;
    }

    public BuckEventBus getBuckEventBus() {
      return eventBus;
    }

    @Override
    public void close() {
      listener.removeEventBus(eventBus);
    }
  }
}
