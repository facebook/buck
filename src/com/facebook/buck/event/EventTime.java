/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event;

import com.facebook.buck.timing.AbsolutePerfTime;
import com.facebook.buck.timing.RelativePerfTime;
import com.google.common.base.Preconditions;

/**
 * Utility class to track cumulative times (wall clock and if supported, user/system CPU times) of
 * an event which may be discontinuous.
 *
 * The event must start and end on the same thread; however, it may subsequently start on
 * a different thread.
 *
 * Note that wall time is tracked in <strong>milliseconds</strong>, but CPU time is tracked in
 * <strong>nanoseconds</strong>.  This is to minimize impedance mismatch with existing APIs.
 */

public class EventTime {
  /** wall clock in milliseconds. */
  private long wallTimeStartMs;
  private long wallTimeTotalMs;

  /** User CPU time in nanoseconds. */
  private long userCpuTimeStartNs;
  private long userCpuTimeTotalNs;

  /** System CPU time in nanoseconds. */
  private long systemCpuTimeStartNs;
  private long systemCpuTimeTotalNs;

  private boolean timingStarted;

  public EventTime() {
    this.wallTimeStartMs = 0;
    this.wallTimeTotalMs = 0;
    this.userCpuTimeStartNs = 0;
    this.userCpuTimeTotalNs = 0;
    this.systemCpuTimeStartNs = 0;
    this.systemCpuTimeTotalNs = 0;

    this.timingStarted = false;
  }

  private boolean hasCpuTime() { return userCpuTimeTotalNs != RelativePerfTime.UNSUPPORTED; }

  public void startTiming(long wallTimeMs, AbsolutePerfTime perfTime) {
    Preconditions.checkState(!timingStarted, "Timing already started");
    Preconditions.checkState(wallTimeMs >= wallTimeTotalMs,
        "Time must be monotonically increasing.");

    wallTimeStartMs = wallTimeMs;

    if (perfTime.hasCpuTime()) {
      userCpuTimeStartNs = perfTime.getUserCpuTimeNs();
      systemCpuTimeStartNs = perfTime.getSystemCpuTimeNs();
    } else {
      userCpuTimeTotalNs = RelativePerfTime.UNSUPPORTED;
      systemCpuTimeTotalNs = RelativePerfTime.UNSUPPORTED;
    }

    timingStarted = true;
  }

  public void stopTiming(long wallTimeMs, AbsolutePerfTime perfTime) {
    Preconditions.checkState(timingStarted, "Timing not started");
    Preconditions.checkState(wallTimeMs >= wallTimeStartMs,
        "Time must be monotonically increasing.");
    wallTimeTotalMs += (wallTimeMs - wallTimeStartMs);
    if (perfTime.hasCpuTime() && hasCpuTime()) {
      userCpuTimeTotalNs += (perfTime.getUserCpuTimeNs() - userCpuTimeStartNs);
      systemCpuTimeTotalNs += (perfTime.getSystemCpuTimeNs() - systemCpuTimeStartNs);
    }

    timingStarted = false;
  }

  /** Get the cumulative event time from the first start to @wallTimeSoFar.
   *
   * @param wallTimeSoFarMs The wall time in ms to consider.  This is ignored if
   *                      the EventTime is not currently in the timing state.
   *
   * @return The wall time in milliseconds that the event has taken so far.
   */
  public long getTotalWallTimeMs(long wallTimeSoFarMs) {
    long wallTimeMs = wallTimeTotalMs;
    if (timingStarted) {
      wallTimeMs += (wallTimeSoFarMs - wallTimeStartMs);
    }
    return wallTimeMs;
  }

  /** Get the cumulative user CPU time in ns from the first start to now.
   *
   * May be called from any thread.
   *
   * @return The time, or UNSUPPORTED if the current environment doesn't support CPU time gathering.
   */
  public RelativePerfTime getTotalPerfTime(AbsolutePerfTime perfTime) {
    long userCpuTimeNs = userCpuTimeTotalNs;
    long systemCpuTimeNs = systemCpuTimeTotalNs;

    if (timingStarted && hasCpuTime()) {
      userCpuTimeNs += (perfTime.getUserCpuTimeNs() - userCpuTimeStartNs);
      systemCpuTimeNs += (perfTime.getSystemCpuTimeNs() - systemCpuTimeStartNs);
    }
    return RelativePerfTime.of(userCpuTimeNs, systemCpuTimeNs);
  }
}
