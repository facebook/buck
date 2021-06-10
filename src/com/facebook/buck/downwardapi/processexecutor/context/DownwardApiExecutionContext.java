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

package com.facebook.buck.downwardapi.processexecutor.context;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.util.timing.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Downward API execution context. */
public final class DownwardApiExecutionContext implements AutoCloseable {

  private static final Logger LOG = Logger.get(DownwardApiExecutionContext.class);

  private final Instant startExecutionInstant;
  private final IsolatedEventBus isolatedEventBus;
  private final Map<Integer, SimplePerfEvent.Started> chromeTraceStartedEvents = new HashMap<>();
  private final Map<Integer, StepEvent.Started> stepStartedEvents = new HashMap<>();
  private final Map<ActionId, Long> actionToThreadIdMap = new HashMap<>();

  private DownwardApiExecutionContext(
      Instant startExecutionInstant, IsolatedEventBus isolatedEventBus, ActionId actionId) {
    this.startExecutionInstant = startExecutionInstant;
    this.isolatedEventBus = isolatedEventBus;
    registerActionId(actionId);
  }

  /** Returns {@code Instant} when execution started. */
  public Instant getStartExecutionInstant() {
    return startExecutionInstant;
  }

  public void registerStartChromeEvent(Integer key, SimplePerfEvent.Started started) {
    chromeTraceStartedEvents.put(key, started);
  }

  @Nullable
  public SimplePerfEvent.Started getChromeTraceStartedEvent(int eventId) {
    return chromeTraceStartedEvents.remove(eventId);
  }

  public void registerStartStepEvent(Integer key, StepEvent.Started started) {
    stepStartedEvents.put(key, started);
  }

  @Nullable
  public StepEvent.Started getStepStartedEvent(int eventId) {
    return stepStartedEvents.remove(eventId);
  }

  public void postEvent(ExternalEvent event) {
    isolatedEventBus.post(event);
  }

  public void postEvent(ConsoleEvent event) {
    isolatedEventBus.post(event);
  }

  public void postEvent(StepEvent event, ActionId actionId, Instant atTime) {
    isolatedEventBus.post(event, actionId, atTime, getThreadId(actionId));
  }

  /** Posts events into buck event bus that occurred at {@code atTime}. */
  public void postEvent(SimplePerfEvent event, ActionId actionId, Instant atTime) {
    isolatedEventBus.post(event, actionId, atTime, getThreadId(actionId));
  }

  private long getThreadId(ActionId actionId) {
    Long threadId = actionToThreadIdMap.get(actionId);
    if (threadId == null) {
      LOG.warn("No thread id registered for action id: %s", actionId);
      return Thread.currentThread().getId();
    }

    return threadId;
  }

  /** Creates {@link DownwardApiExecutionContext} */
  public static DownwardApiExecutionContext of(
      IsolatedEventBus buckEventBus, Clock clock, ActionId actionId) {
    return new DownwardApiExecutionContext(
        Instant.ofEpochMilli(clock.currentTimeMillis()), buckEventBus, actionId);
  }

  /** Register action id with this context. Stores mapping between action id and invoking thread. */
  public void registerActionId(ActionId actionId) {
    long threadId = Thread.currentThread().getId();
    Long previousThreadId = actionToThreadIdMap.put(actionId, threadId);
    if (previousThreadId != null && !previousThreadId.equals(threadId)) {
      LOG.warn(
          "Action id to thread id mapping overwritten. Action id: %s, prev thread id: %s, new thread id: %s",
          actionId, previousThreadId, threadId);
    }
  }

  @Override
  public void close() {
    verifyAllEventsProcessed();
    chromeTraceStartedEvents.clear();
    stepStartedEvents.clear();
    actionToThreadIdMap.clear();
  }

  private void verifyAllEventsProcessed() {
    int eventsSize = chromeTraceStartedEvents.size() + stepStartedEvents.size();
    boolean hasUnprocessed = eventsSize > 0;
    if (hasUnprocessed) {
      LOG.warn(
          "There are %s unprocessed events%n. Unprocessed events: stepStarted: %s, chromeTraceStarted: %s",
          eventsSize, stepStartedEvents.values(), chromeTraceStartedEvents.values());
    }
  }
}
