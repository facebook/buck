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

import java.time.Instant;

/**
 * Thin wrapper around guava event bus.
 *
 * <p>This interface exists only to break circular Buck target dependencies.
 */
public interface BuckEventBus extends IsolatedEventBus, EventDispatcher {

  /** Register a listener to process events */
  void register(Object object);

  /** Remove a listener previously specified with {@code register()} */
  void unregister(Object object);

  /**
   * Wait for all currently running events to dispatch and finish executing
   *
   * @param timeout Time in milliseconds to wait for completion, if timeout is not greater than 0
   *     then it will wait indefinitely
   * @return true if all events were handled and false if timeout was hit
   */
  boolean waitEvents(long timeout);

  /**
   * TODO(irenewchen): Remove the methods below after BuckEventBus no longer extends
   * IsolatedEventBus.
   */

  /** Post event to the EventBus. */
  @Override
  default void post(ConsoleEvent event) {
    post((BuckEvent) event);
  }

  @Override
  default void post(ConsoleEvent event, long threadId) {
    post((BuckEvent) event, threadId);
  }

  @Override
  default void post(StepEvent event) {
    post((BuckEvent) event);
  }

  @Override
  default void post(StepEvent event, long threadId) {
    post((BuckEvent) event, threadId);
  }

  @Override
  default void post(StepEvent event, Instant atTime, long threadId) {
    post((BuckEvent) event, atTime, threadId);
  }

  @Override
  default void post(SimplePerfEvent event) {
    post((BuckEvent) event);
  }

  /** Post event to the EventBus using the given {@code atTime} timestamp and {@code threadId}. */
  @Override
  default void post(SimplePerfEvent event, Instant atTime, long threadId) {
    post((BuckEvent) event, atTime, threadId);
  }

  /** Post event to the EventBus using the given {@code atTime} timestamp. */
  @Override
  default void post(SimplePerfEvent event, Instant atTime) {
    post((BuckEvent) event, atTime);
  }

  /** Post event to the EventBus that occurred in the passing {@code threadId}. */
  @Override
  default void post(SimplePerfEvent event, long threadId) {
    post((BuckEvent) event, threadId);
  }

  /** Post already configured event to the EventBus. */
  @Override
  default void postWithoutConfiguring(SimplePerfEvent event) {
    postWithoutConfiguring((BuckEvent) event);
  }

  @Override
  default void timestamp(ConsoleEvent event) {
    timestamp((BuckEvent) event);
  }

  @Override
  default void timestamp(StepEvent event) {
    timestamp((BuckEvent) event);
  }

  /**
   * Timestamp event. A timestamped event cannot subsequently being posted and is useful only to
   * pass its timestamp on to another posted event.
   */
  @Override
  default void timestamp(SimplePerfEvent event) {
    timestamp((BuckEvent) event);
  }

  /**
   * Timestamp event that occurred in the passing {@code threadId}. A timestamped event cannot
   * subsequently being posted and is useful only to pass its timestamp on to another posted event.
   */
  @Override
  default void timestamp(SimplePerfEvent event, long threadId) {
    timestamp((BuckEvent) event, threadId);
  }

  /** Returns an {@link IsolatedEventBus} representation of this event bus. */
  IsolatedEventBus isolated();
}
