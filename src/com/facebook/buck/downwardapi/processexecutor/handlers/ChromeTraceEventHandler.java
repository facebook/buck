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

package com.facebook.buck.downwardapi.processexecutor.handlers;

import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiExecutionContext;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.PerfEventId;
import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Downward API event handler for {@code ChromeTraceEvent} */
enum ChromeTraceEventHandler implements EventHandler<ChromeTraceEvent> {
  INSTANCE;

  @Override
  public void handleEvent(DownwardApiExecutionContext context, ChromeTraceEvent event) {
    Instant timestamp = EventHandler.getTimestamp(context, event.getDuration());
    Map<Integer, SimplePerfEvent.Started> chromeTraceStartedEvents =
        context.getChromeTraceStartedEvents();

    ImmutableMap<String, Object> attributes =
        ImmutableMap.<String, Object>builder().putAll(event.getDataMap()).build();
    int eventId = event.getEventId();

    SimplePerfEvent.Started started;
    switch (event.getStatus()) {
      case BEGIN:
        PerfEventId perfEventId = PerfEventId.of(String.valueOf(eventId));
        started = SimplePerfEvent.started(perfEventId, event.getCategory(), attributes);
        chromeTraceStartedEvents.put(eventId, started);
        context.postEvent(started, timestamp);
        break;

      case END:
        started =
            Objects.requireNonNull(
                chromeTraceStartedEvents.remove(eventId),
                "Started chrome trace event for event id: " + eventId + " is not found");
        BuckEvent finishedEvent = started.createFinishedEvent(attributes);
        context.postEvent(finishedEvent, timestamp);
        break;

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException(
            "Chrome trace status: " + event.getStatus() + " is not supported!");
    }
  }
}
