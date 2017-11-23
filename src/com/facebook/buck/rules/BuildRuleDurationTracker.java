/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.timing.ClockDuration;
import com.google.common.annotations.VisibleForTesting;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Tracks the total duration of work spent on each build rule.
 *
 * <p>Computation associated with build rules are broken into several phases. Those phases are
 * invoked from various places in {@link CachingBuildEngine} at various times and on various
 * threads. In order to track the total duration of all of those phases combined we need some
 * central store, i.e. this class.
 */
public class BuildRuleDurationTracker {

  private final ConcurrentMap<BuildTarget, DurationHolder> durations = new ConcurrentHashMap<>();

  /** This method should only be used for testing. */
  @VisibleForTesting
  public void setDuration(BuildRule rule, ClockDuration duration) {
    durations.put(rule.getBuildTarget(), new DurationHolder(duration));
  }

  public ClockDuration doBeginning(BuildRule rule, long wallMillisTime, long nanoTime) {
    return durations
        .computeIfAbsent(rule.getBuildTarget(), (key) -> new DurationHolder())
        .doBeginning(wallMillisTime, nanoTime);
  }

  public ClockDuration doEnding(
      BuildRule rule, long wallMillisTime, long nanoTime, long threadUserNanoDuration) {
    return durations
        .computeIfAbsent(rule.getBuildTarget(), (key) -> new DurationHolder())
        .doEnding(wallMillisTime, nanoTime, threadUserNanoDuration);
  }

  @ThreadSafe
  private static class DurationHolder {
    // intervals can be nested so we need to keep the nesting count
    @GuardedBy("this")
    private int inProgressCount = 0;

    // start time of the current in-progress interval
    @GuardedBy("this")
    private long wallMillisStarted = 0;

    @GuardedBy("this")
    private long nanoStarted = 0;

    // accumulated duration till the current in-progress interval
    @GuardedBy("this")
    private long wallMillisDuration;

    @GuardedBy("this")
    private long nanoDuration;

    @GuardedBy("this")
    private long threadUserNanoDuration;

    public DurationHolder() {
      this(ClockDuration.ZERO);
    }

    public DurationHolder(ClockDuration initialDuration) {
      wallMillisDuration = initialDuration.getWallMillisDuration();
      nanoDuration = initialDuration.getNanoDuration();
      threadUserNanoDuration = initialDuration.getThreadUserNanoDuration();
    }

    public synchronized ClockDuration getDurationAt(long wallMillisTime, long nanoTime) {
      return new ClockDuration(
          wallMillisDuration + wallMillisTime - wallMillisStarted,
          nanoDuration + nanoTime - nanoStarted,
          threadUserNanoDuration);
    }

    public synchronized ClockDuration doBeginning(long wallMillisTime, long nanoTime) {
      if (inProgressCount++ == 0) {
        wallMillisStarted = wallMillisTime;
        nanoStarted = nanoTime;
      }
      return getDurationAt(wallMillisTime, nanoTime);
    }

    public synchronized ClockDuration doEnding(
        long wallMillisTime, long nanoTime, long threadUserNanoDuration) {
      this.threadUserNanoDuration += threadUserNanoDuration;
      ClockDuration duration = getDurationAt(wallMillisTime, nanoTime);
      if (--inProgressCount == 0) {
        wallMillisDuration += wallMillisTime - wallMillisStarted;
        nanoDuration += nanoTime - nanoStarted;
      }
      return duration;
    }
  }
}
