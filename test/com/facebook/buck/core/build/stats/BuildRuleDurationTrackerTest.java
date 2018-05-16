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

package com.facebook.buck.core.build.stats;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.util.timing.ClockDuration;
import org.junit.Test;

public class BuildRuleDurationTrackerTest {

  @Test
  public void test() {
    BuildRuleDurationTracker tracker = new BuildRuleDurationTracker();
    BuildRule rule1 = new FakeBuildRule("//fake:rule1");
    BuildRule rule2 = new FakeBuildRule("//fake:rule2");

    /**
     * 10 15 20 22 23 26 30 31 33 35 37 . . . . . . . . . . . |-rule1-| . . . . . . . . .
     * |---rule1---| . . . . . |-------rule1-------| . . |---rule1---| . . . |------rule1------| . .
     * |--------rule2-------|
     *
     * <p>Note that the total wall time for rule1 is 5 + 17 = 22. We count overlaps only once.
     * Thread time on the other hand is always added, regardless of overlapping.
     *
     * <p>Durations for rule1 and rule2 are completely independent of each other.
     */
    assertEquals(new ClockDuration(0, 0, 0), tracker.doBeginning(rule1, 10, 10000));
    assertEquals(new ClockDuration(5, 5000, 900), tracker.doEnding(rule1, 15, 15000, 900));
    assertEquals(new ClockDuration(5, 5000, 900), tracker.doBeginning(rule1, 20, 20000));
    assertEquals(new ClockDuration(7, 7000, 900), tracker.doBeginning(rule1, 22, 22000));
    assertEquals(new ClockDuration(8, 8000, 900), tracker.doBeginning(rule1, 23, 23000));
    assertEquals(new ClockDuration(0, 0, 0), tracker.doBeginning(rule2, 23, 23000));
    assertEquals(new ClockDuration(11, 11000, 1200), tracker.doEnding(rule1, 26, 26000, 300));
    assertEquals(new ClockDuration(15, 15000, 1200), tracker.doBeginning(rule1, 30, 30000));
    assertEquals(new ClockDuration(16, 16000, 1300), tracker.doEnding(rule1, 31, 31000, 100));
    assertEquals(new ClockDuration(18, 18000, 1500), tracker.doEnding(rule1, 33, 33000, 200));
    assertEquals(new ClockDuration(12, 12000, 42), tracker.doEnding(rule2, 35, 35000, 42));
    assertEquals(new ClockDuration(22, 22000, 1900), tracker.doEnding(rule1, 37, 37000, 400));
  }
}
