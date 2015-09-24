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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.timing.AbsolutePerfTime;
import com.facebook.buck.timing.RelativePerfTime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class EventTimeTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void eventTimeWithCpuTimeSupport() {
    EventTime eventTime = new EventTime();
    eventTime.startTiming(1, AbsolutePerfTime.of(10, 100));
    assertEquals(eventTime.getTotalWallTimeMs(3), 2);
    assertEquals(
        eventTime.getTotalPerfTime(
            AbsolutePerfTime.of(30, 300)),
            RelativePerfTime.of(20, 200));

    eventTime.stopTiming(3, AbsolutePerfTime.of(31, 301));
    assertEquals(eventTime.getTotalWallTimeMs(4), 2);
    assertEquals(eventTime.getTotalPerfTime(AbsolutePerfTime.of(40, 400)),
            RelativePerfTime.of(21, 201));

    assertEquals(eventTime.getTotalWallTimeMs(5), 2);
    assertEquals(eventTime.getTotalPerfTime(AbsolutePerfTime.of(50, 500)),
            RelativePerfTime.of(21, 201));

    eventTime.startTiming(4, AbsolutePerfTime.of(5, 50));
    assertEquals(eventTime.getTotalWallTimeMs(5), 3);
    assertEquals(eventTime.getTotalPerfTime(AbsolutePerfTime.of(10, 100)),
            RelativePerfTime.of(26, 251));

    eventTime.stopTiming(4, AbsolutePerfTime.of(5, 50));
    assertEquals(eventTime.getTotalWallTimeMs(4), 2);
    assertEquals(eventTime.getTotalPerfTime(AbsolutePerfTime.of(40, 400)),
            RelativePerfTime.of(21, 201));
  }

  @Test
  public void eventTimeTestStartWithoutStop() {
    EventTime eventTime = new EventTime();
    eventTime.startTiming(1, AbsolutePerfTime.of(10, 100));

    thrown.expect(IllegalStateException.class);
    eventTime.startTiming(1, AbsolutePerfTime.of(10, 100));
  }

  @Test
  public void eventTimeTestStopWithoutStart() {
    EventTime eventTime = new EventTime();

    thrown.expect(IllegalStateException.class);
    eventTime.stopTiming(1, AbsolutePerfTime.of(10, 100));
  }

  @Test
  public void eventTimeWithoutCpuTimeSupport() {
    EventTime eventTime = new EventTime();
    eventTime.startTiming(1, AbsolutePerfTime.of(
            AbsolutePerfTime.UNSUPPORTED, AbsolutePerfTime.UNSUPPORTED));
    eventTime.stopTiming(3, AbsolutePerfTime.of(30, 300));
    assertEquals(eventTime.getTotalWallTimeMs(4), 2);
    assertEquals(eventTime.getTotalPerfTime(AbsolutePerfTime.of(40, 400)),
            RelativePerfTime.of(RelativePerfTime.UNSUPPORTED, RelativePerfTime.UNSUPPORTED));
  }
}
