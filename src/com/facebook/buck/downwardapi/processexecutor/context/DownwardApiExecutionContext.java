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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Downward API execution context. */
@BuckStyleValue
public abstract class DownwardApiExecutionContext {

  private final ConcurrentHashMap<Integer, StepEvent.Started> stepStartedEvents =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, SimplePerfEvent.Started> chromeTraceStartedEvents =
      new ConcurrentHashMap<>();

  /** Returns {@code Instant} when execution started. */
  public abstract Instant getStartExecutionInstant();

  abstract IsolatedEventBus getBuckEventBus();

  abstract long getInvokingThreadId();

  public ConcurrentHashMap<Integer, SimplePerfEvent.Started> getChromeTraceStartedEvents() {
    return chromeTraceStartedEvents;
  }

  public ConcurrentHashMap<Integer, StepEvent.Started> getStepStartedEvents() {
    return stepStartedEvents;
  }

  public final void postEvent(ExternalEvent event) {
    getBuckEventBus().post(event, getInvokingThreadId());
  }

  public final void postEvent(ConsoleEvent event) {
    getBuckEventBus().post(event, getInvokingThreadId());
  }

  public final void postEvent(StepEvent event) {
    getBuckEventBus().post(event, getInvokingThreadId());
  }

  public final void postEvent(StepEvent event, Instant atTime) {
    getBuckEventBus().post(event, atTime, getInvokingThreadId());
  }

  /** Posts events into buck event bus. */
  public final void postEvent(SimplePerfEvent event) {
    getBuckEventBus().post(event, getInvokingThreadId());
  }

  /** Posts events into buck event bus that occurred at {@code atTime}. */
  public final void postEvent(SimplePerfEvent event, Instant atTime) {
    getBuckEventBus().post(event, atTime, getInvokingThreadId());
  }

  public static DownwardApiExecutionContext of(IsolatedEventBus buckEventBus) {
    return ImmutableDownwardApiExecutionContext.ofImpl(
        Instant.now(), buckEventBus, Thread.currentThread().getId());
  }
}
