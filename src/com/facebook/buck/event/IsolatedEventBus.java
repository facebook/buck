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

import com.facebook.buck.core.model.BuildId;
import java.io.Closeable;
import java.time.Instant;

/** Minimal event bus that does not know about core buck concepts. */
public interface IsolatedEventBus extends Closeable {

  /** Returns the {@link BuildId} associated with the current build. */
  BuildId getBuildId();

  /** Post a {@link ConsoleEvent} to the this event bus. */
  void post(ConsoleEvent event);

  /** Post a {@link ConsoleEvent} that occurred in the given {@code threadId} to this event bus. */
  void post(ConsoleEvent event, long threadId);

  /** Post a {@link ExternalEvent} to the this event bus. */
  void post(ExternalEvent event);

  /** Post a {@link ExternalEvent} that occurred in the given {@code threadId} to this event bus. */
  void post(ExternalEvent event, long threadId);

  /** Post a {@link StepEvent} to the this event bus. */
  void post(StepEvent event, String actionId);

  /** Post a {@link StepEvent} that occurred in the given {@code threadId} to this event bus. */
  void post(StepEvent event, String actionId, long threadId);

  /**
   * Post a {@link StepEvent}t that occurred in the given {@code threadId} to this event bus using
   * the given {@code atTime} timestamp.
   */
  void post(StepEvent event, String actionId, Instant atTime, long threadId);

  /** Post a {@link SimplePerfEvent} to the this event bus. */
  void post(SimplePerfEvent event, String actionId);

  /** Post a {@link SimplePerfEvent} to this event bus using the given {@code atTime} timestamp. */
  void post(SimplePerfEvent event, String actionId, Instant atTime);

  /**
   * Post a {@link SimplePerfEvent} that occurred in the given {@code threadId} to this event bus.
   */
  void post(SimplePerfEvent event, String actionId, long threadId);

  /**
   * Post a {@link SimplePerfEvent}t that occurred in the given {@code threadId} to this event bus
   * using the given {@code atTime} timestamp.
   */
  void post(SimplePerfEvent event, String actionId, Instant atTime, long threadId);

  /** Post an already configured {@link SimplePerfEvent} to this event bus. */
  void postWithoutConfiguring(SimplePerfEvent event, String actionId);

  /**
   * Timestamp the given {@link ConsoleEvent} that occurred in the current thread. A timestamped
   * event cannot subsequently being posted and is useful only to pass its timestamp on to another
   * posted event.
   */
  void timestamp(ConsoleEvent event);

  /**
   * Timestamp the given {@link StepEvent} that occurred in the current thread. A timestamped event
   * cannot subsequently being posted and is useful only to pass its timestamp on to another posted
   * event.
   */
  void timestamp(StepEvent event);

  /**
   * Timestamp the given {@link SimplePerfEvent} that occurred in the current thread. A timestamped
   * event cannot subsequently being posted and is useful only to pass its timestamp on to another
   * posted event.
   */
  void timestamp(SimplePerfEvent event);

  /**
   * Timestamp the given {@link SimplePerfEvent} that occurred in the given {@code threadId}. A
   * timestamped event cannot subsequently being posted and is useful only to pass its timestamp on
   * to another posted event.
   */
  void timestamp(SimplePerfEvent event, long threadId);

  /** Waits till all events processed. */
  void waitTillAllEventsProcessed();
}
