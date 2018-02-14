/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import java.util.Set;

/**
 * A simple class that posts log events from skylark as {@link ConsoleEvent} the provided event bus
 */
public class ConsoleEventHandler implements EventHandler {

  private final BuckEventBus eventBus;
  private final Set<EventKind> supportedEvents;

  /**
   * Create an instance of {@link ConsoleEventHandler} that posts to {@code eventBus}
   *
   * @param eventBus The event bus to post {@link ConsoleEvent} to
   * @param supportedEvents The events that should post to the event bus
   */
  public ConsoleEventHandler(BuckEventBus eventBus, Set<EventKind> supportedEvents) {
    this.eventBus = eventBus;
    this.supportedEvents = supportedEvents;
  }

  private static String getConsoleMessage(Event event) {
    /**
     * Pulled from @{link com.google.devtools.build.lib.events.PrintingEventHandler}, without the
     * newline at the end
     */
    StringBuilder builder = new StringBuilder();
    builder.append(event.getKind()).append(": ");
    if (event.getLocation() != null) {
      builder.append(event.getLocation().print()).append(": ");
    }
    builder.append(event.getMessage());
    return builder.toString();
  }

  @Override
  public void handle(Event event) {
    // There are some of these types that don't really have good analogs for Buck. Just print them
    // directly to stderr
    if (!supportedEvents.contains(event.getKind())) {
      return;
    }
    switch (event.getKind()) {
      case WARNING:
        eventBus.post(ConsoleEvent.warning(getConsoleMessage(event)));
        break;
      case STDERR:
      case INFO:
        eventBus.post(ConsoleEvent.info(getConsoleMessage(event)));
        break;
      case PROGRESS:
      case START:
      case FINISH:
      case SUBCOMMAND:
      case FAIL:
      case TIMEOUT:
      case DEPCHECKER:
      case ERROR:
        eventBus.post(ConsoleEvent.severe(getConsoleMessage(event)));
        break;
      case DEBUG:
      case PASS:
      case STDOUT:
      default:
        eventBus.post(ConsoleEvent.info(getConsoleMessage(event)));
        break;
    }
  }
}
