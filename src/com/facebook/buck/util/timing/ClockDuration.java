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

package com.facebook.buck.util.timing;

import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;

/** Represents a difference between two time points obtained by {@link Clock}. */
public class ClockDuration {

  public static final ClockDuration ZERO = new ClockDuration(0, 0, 0);

  /** @see Clock#currentTimeMillis() */
  private final long wallMillisDuration;

  /** @see Clock#nanoTime() */
  private final long nanoDuration;

  /** @see Clock#threadUserNanoTime(long) */
  private final long threadUserNanoDuration;

  public ClockDuration(long wallMillisDuration, long nanoDuration, long threadUserNanoDuration) {
    this.wallMillisDuration = wallMillisDuration;
    this.nanoDuration = nanoDuration;
    this.threadUserNanoDuration = threadUserNanoDuration;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public long getWallMillisDuration() {
    return wallMillisDuration;
  }

  public long getNanoDuration() {
    return nanoDuration;
  }

  public long getThreadUserNanoDuration() {
    return threadUserNanoDuration;
  }

  @Override
  public int hashCode() {
    int hash = Long.hashCode(wallMillisDuration);
    hash = hash * 31 + Long.hashCode(nanoDuration);
    hash = hash * 31 + Long.hashCode(threadUserNanoDuration);
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof ClockDuration)) {
      return false;
    }
    ClockDuration that = (ClockDuration) obj;
    return this.wallMillisDuration == that.wallMillisDuration
        && this.nanoDuration == that.nanoDuration
        && this.threadUserNanoDuration == that.threadUserNanoDuration;
  }

  @Override
  public String toString() {
    return String.format(
        "{wall: %d, nano: %d, thread: %d}",
        wallMillisDuration, nanoDuration, threadUserNanoDuration);
  }
}
