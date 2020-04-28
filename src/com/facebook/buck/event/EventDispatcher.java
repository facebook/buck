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

/** Thin wrapper around guava event bus. */
public interface EventDispatcher {

  /** Post event to the EventBus. */
  void post(BuckEvent event);

  /** Post event to the EventBus using the given {@code atTime} timestamp and {@code threadId}. */
  void post(BuckEvent event, Instant atTime, long threadId);

  /** Post event to the EventBus using the given {@code atTime} timestamp. */
  void post(BuckEvent event, Instant atTime);

  /** Post event to the EventBus that occurred in the passing {@code threadId}. */
  void post(BuckEvent event, long threadId);

  /** Post already configured event to the EventBus. */
  void postWithoutConfiguring(BuckEvent event);

  /**
   * Timestamp event. A timestamped event cannot subsequently being posted and is useful only to
   * pass its timestamp on to another posted event.
   */
  void timestamp(BuckEvent event);

  /**
   * Timestamp event that occurred in the passing {@code threadId}. A timestamped event cannot
   * subsequently being posted and is useful only to pass its timestamp on to another posted event.
   */
  void timestamp(BuckEvent event, long threadId);
}
