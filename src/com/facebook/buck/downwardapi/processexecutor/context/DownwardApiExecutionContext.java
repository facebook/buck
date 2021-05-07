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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Downward API execution context. */
public final class DownwardApiExecutionContext {

  private final Instant startExecutionInstant;
  private final IsolatedEventBus isolatedEventBus;
  private final long invokingThreadId;
  private final Map<Integer, SimplePerfEvent.Started> chromeTraceStartedEvents = new HashMap<>();
  private final Map<Integer, StepEvent.Started> stepStartedEvents = new HashMap<>();

  private DownwardApiExecutionContext(
      Instant startExecutionInstant, IsolatedEventBus isolatedEventBus, long invokingThreadId) {
    this.startExecutionInstant = startExecutionInstant;
    this.isolatedEventBus = isolatedEventBus;
    this.invokingThreadId = invokingThreadId;
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
    isolatedEventBus.post(event, invokingThreadId);
  }

  public void postEvent(ConsoleEvent event) {
    isolatedEventBus.post(event, invokingThreadId);
  }

  public void postEvent(StepEvent event) {
    isolatedEventBus.post(event, invokingThreadId);
  }

  public void postEvent(StepEvent event, Instant atTime) {
    isolatedEventBus.post(event, atTime, invokingThreadId);
  }

  /** Posts events into buck event bus. */
  public void postEvent(SimplePerfEvent event) {
    isolatedEventBus.post(event, invokingThreadId);
  }

  /** Posts events into buck event bus that occurred at {@code atTime}. */
  public void postEvent(SimplePerfEvent event, Instant atTime) {
    isolatedEventBus.post(event, atTime, invokingThreadId);
  }

  /** Creates {@link DownwardApiExecutionContext} */
  public static DownwardApiExecutionContext of(IsolatedEventBus buckEventBus, Clock clock) {
    return new DownwardApiExecutionContext(
        Instant.ofEpochMilli(clock.currentTimeMillis()),
        buckEventBus,
        Thread.currentThread().getId());
  }

  /**
   * Creates {@link DownwardApiExecutionContext} from the existing {@code context}, but with a new
   * {@code threadId}
   */
  public static DownwardApiExecutionContext from(
      DownwardApiExecutionContext context, long threadId) {
    int eventsSize = context.chromeTraceStartedEvents.size() + context.stepStartedEvents.size();
    Preconditions.checkState(eventsSize == 0, "There are " + eventsSize + " unprocessed events.");

    return new DownwardApiExecutionContext(
        context.getStartExecutionInstant(), context.isolatedEventBus, threadId);
  }
}
