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

package com.facebook.buck.event;

import com.facebook.buck.event.PerfEvents.AggregationSupportedEvent.Finished;
import com.facebook.buck.event.PerfEvents.AggregationSupportedEvent.Started;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableMap;

/** Perf event utils */
public class PerfEvents {

  private PerfEvents() {}

  /** Creates a simple scoped leaf event that will be logged to superconsole, chrome traces, etc. */
  public static Scope scope(BuckEventBus eventBus, String category) {
    return scope(eventBus.isolated(), category);
  }

  /** Creates a simple scoped leaf event that will be logged to superconsole, chrome traces, etc. */
  public static Scope scope(IsolatedEventBus eventBus, String category) {
    return scope(eventBus, category, true);
  }

  /**
   * @param category the name of the category.
   * @param logToChromeTrace if it should be logged to the ChromeTrace or not.
   */
  public static Scope scope(IsolatedEventBus eventBus, String category, boolean logToChromeTrace) {
    Started started = new Started(EventKey.unique(), category, logToChromeTrace);
    eventBus.post(started);
    return () -> eventBus.post(new Finished(started));
  }

  /** Base class that extends {@link SimplePerfEvent} and supports aggregation. */
  public abstract static class AggregationSupportedEvent extends SimplePerfEvent {
    private final String category;
    private final boolean logToChromeTrace;
    private final Type eventType;

    private AggregationSupportedEvent(
        EventKey eventKey, String category, boolean logToChromeTrace, Type eventType) {
      super(eventKey);
      this.category = category;
      this.logToChromeTrace = logToChromeTrace;
      this.eventType = eventType;
    }

    @Override
    public String getCategory() {
      return category;
    }

    @Override
    public String getEventName() {
      return category;
    }

    @Override
    public final boolean isLogToChromeTrace() {
      return logToChromeTrace;
    }

    @Override
    public final boolean supportsAggregation() {
      return true;
    }

    @Override
    public PerfEventTitle getTitle() {
      return PerfEventTitle.of(category);
    }

    @Override
    public ImmutableMap<String, Object> getEventInfo() {
      return ImmutableMap.of("description", toString());
    }

    @Override
    public Type getEventType() {
      return eventType;
    }

    @Override
    protected String getValueString() {
      return getEventType().getValue();
    }

    /** Started event for {@link AggregationSupportedEvent}. */
    public static class Started extends AggregationSupportedEvent {
      private Started(EventKey eventKey, String category, boolean logToChromeTrace) {
        super(eventKey, category, logToChromeTrace, Type.STARTED);
      }
    }

    /** Finished event for {@link AggregationSupportedEvent}. */
    public static class Finished extends AggregationSupportedEvent {
      private Finished(AggregationSupportedEvent.Started started) {
        super(
            started.getEventKey(),
            started.getCategory(),
            started.isLogToChromeTrace(),
            Type.FINISHED);
      }
    }
  }
}
