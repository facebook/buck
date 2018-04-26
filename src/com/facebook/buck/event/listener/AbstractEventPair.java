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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import java.util.Optional;
import org.immutables.value.Value;

/** Utility class to help match up start and end events */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractEventPair {
  @Value.Parameter
  public abstract Optional<BuckEventExternalInterface> getStart();

  @Value.Parameter
  public abstract Optional<BuckEventExternalInterface> getFinish();

  /** @return true if this event pair has a start and an end, false otherwise. */
  public boolean isComplete() {
    return getStart().isPresent() && getFinish().isPresent();
  }

  /** @return true if this event pair has been started, but has not yet been finished. */
  public boolean isOngoing() {
    return getStart().isPresent() && !getFinish().isPresent();
  }

  /** @return the start time of this event or -1 if this pair does not contain a start */
  public long getStartTime() {
    return getStart().isPresent() ? getStart().get().getTimestamp() : -1;
  }

  /** @return the end time of this event or -1 if this pair does not contain an end */
  public long getEndTime() {
    return getFinish().isPresent() ? getFinish().get().getTimestamp() : -1;
  }

  /**
   * @return the difference between the start and end events in ms if this event pair is complete, 0
   *     otherwise.
   */
  public long getElapsedTimeMs() {
    if (isComplete()) {
      return getEndTime() - getStartTime();
    } else {
      return 0L;
    }
  }

  /**
   * Build a proxy event pair from a start and end timestamp
   *
   * @param start the start time of the resulting pair
   * @param end the end time of the resulting pair
   * @return an event pair made from two proxy (synthetic) events
   */
  public static EventPair proxy(long start, long end) {
    return EventPair.of(Optional.of(ProxyBuckEvent.of(start)), Optional.of(ProxyBuckEvent.of(end)));
  }
}
