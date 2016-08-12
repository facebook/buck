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

public class BroadcastEventListener {

  private Set<BroadcastEventBusClosable> eventBusesClosable;

  public BroadcastEventListener() {
    eventBusesClosable = new HashSet<>();
  }

  public void broadcast(BroadcastEvent event) {
    if (eventBusesClosable.isEmpty()) {
      throw new RuntimeException("No available eventBus to broadcast event: " +
          event.getEventName());
    }
    postToAllBuses(event);
  }

  public BroadcastEventBusClosable addEventBus(BuckEventBus eventBus) {
    BroadcastEventBusClosable eventBusClosable = new BroadcastEventBusClosable(this, eventBus);
    eventBusesClosable.add(eventBusClosable);
    return eventBusClosable;
  }

  private void postToAllBuses(BroadcastEvent event) {
    for (BroadcastEventBusClosable eventBusClosable : eventBusesClosable) {
      if (event.isConfigured()) {
        eventBusClosable.getBuckEventBus().postWithoutConfiguring(event);
      } else {
        eventBusClosable.getBuckEventBus().post(event);
      }
    }
  }

  private void removeEventBus(BuckEventBus eventBus) {
    for (BroadcastEventBusClosable eventBusClosable : eventBusesClosable) {
      if (eventBusClosable.getBuckEventBus().equals(eventBus)) {
        eventBusesClosable.remove(eventBusClosable);
        return;
      }
    }
  }

  public class BroadcastEventBusClosable implements AutoCloseable {
    BroadcastEventListener listener;
    BuckEventBus eventBus;

    public BroadcastEventBusClosable (
        BroadcastEventListener listener,
        BuckEventBus bus) {
      this.listener = listener;
      this.eventBus = bus;
    }

    public BroadcastEventListener getListener() {
      return listener;
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
