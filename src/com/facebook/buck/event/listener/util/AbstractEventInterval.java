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
package com.facebook.buck.event.listener.util;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.util.OptionalLong;
import org.immutables.value.Value;

/** Utility class to help match up start and end events */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractEventInterval {
  @Value.Parameter
  public abstract OptionalLong getStart();

  @Value.Parameter
  public abstract OptionalLong getFinish();

  /** @return true if this event pair has a start. */
  public boolean isStarted() {
    return getStart().isPresent();
  }

  /** @return true if this event pair has a start and an end, false otherwise. */
  public boolean isComplete() {
    return isStarted() && getFinish().isPresent();
  }

  /** @return true if this event pair has been started, but has not yet been finished. */
  public boolean isOngoing() {
    return isStarted() && !getFinish().isPresent();
  }

  /** @return the start time of this event or -1 if this pair does not contain a start */
  public long getStartTime() {
    return getStart().orElse(-1);
  }

  /** @return the end time of this event or -1 if this pair does not contain an end */
  public long getEndTime() {
    return getFinish().orElse(-1);
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
  public static EventInterval proxy(long start, long end) {
    return EventInterval.of(OptionalLong.of(start), OptionalLong.of(end));
  }
}
