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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.Test;

/** Test static helper functions in {@link AbstractConsoleEventBusListener} */
public class AbstractConsoleEventBusListenerTest {

  private static AbstractConsoleEventBusListener createAbstractConsoleInstance() {
    return new AbstractConsoleEventBusListener(
        new RenderingConsole(FakeClock.doNotCare(), Console.createNullConsole()),
        FakeClock.doNotCare(),
        Locale.US,
        new DefaultExecutionEnvironment(
            EnvVariablesProvider.getSystemEnv(), System.getProperties()),
        false,
        1,
        false) {
      @Override
      public void printSevereWarningDirectly(String line) {}
    };
  }

  @Test
  public void testApproximateDistBuildProgressDoesNotLosePrecision() {
    AbstractConsoleEventBusListener listener = createAbstractConsoleInstance();

    listener.distBuildTotalRulesCount = 0;
    listener.distBuildFinishedRulesCount = 0;
    assertEquals(Optional.of(0.0), listener.getApproximateDistBuildProgress());

    listener.distBuildTotalRulesCount = 100;
    listener.distBuildFinishedRulesCount = 50;
    assertEquals(Optional.of(0.5), listener.getApproximateDistBuildProgress());

    listener.distBuildTotalRulesCount = 17;
    listener.distBuildFinishedRulesCount = 4;
    assertEquals(Optional.of(0.23), listener.getApproximateDistBuildProgress());
  }

  @Test
  public void testGetEventsBetween() {
    EventInterval zeroToOneHundred = EventInterval.proxy(0, 100);
    EventInterval oneToTwoHundred = EventInterval.proxy(100, 200);
    EventInterval twoToThreeHundred = EventInterval.proxy(200, 300);
    EventInterval threeToFourHundred = EventInterval.proxy(300, 400);
    EventInterval fourToFiveHundred = EventInterval.proxy(400, 500);
    List<EventInterval> events =
        ImmutableList.<EventInterval>builder()
            .add(zeroToOneHundred)
            .add(oneToTwoHundred)
            .add(twoToThreeHundred)
            .add(threeToFourHundred)
            .add(fourToFiveHundred)
            .build();

    Collection<EventInterval> fiftyToThreeHundred =
        AbstractConsoleEventBusListener.getEventsBetween(50, 300, events);

    // First event should have been replaced by a proxy
    assertFalse(
        "0-100 event straddled a boundary, should have been replaced by a proxy of 50-100",
        fiftyToThreeHundred.contains(zeroToOneHundred));
    assertTrue(
        "0-100 event straddled a boundary, should have been replaced by a proxy of 50-100",
        fiftyToThreeHundred.contains(EventInterval.proxy(50, 100)));

    // Second and third events should be present in their entirety
    assertTrue(
        "Second event (100-200) is totally contained, so it should pass the filter",
        fiftyToThreeHundred.contains(oneToTwoHundred));
    assertTrue(
        "Third event (200-300) matches the boundary which should be inclusive",
        fiftyToThreeHundred.contains(twoToThreeHundred));

    // Fourth event should have been trimmed to a proxy
    assertFalse(
        "Fourth event (300-400) starts on a boundary, so it should have been proxied",
        fiftyToThreeHundred.contains(threeToFourHundred));
    assertTrue(
        "Fourth event (300-400) starts on a boundary, so it should have been proxied",
        fiftyToThreeHundred.contains(EventInterval.proxy(300, 300)));

    // Fifth event should be left out
    assertFalse(
        "Fifth event (400-500) is totally out of range, should be absent",
        fiftyToThreeHundred.contains(fourToFiveHundred));
  }

  @Test
  public void testGetWorkingTimeFromLastStartUntilNowIsNegOneForClosedPairs() {
    EventInterval closed = EventInterval.proxy(100, 500);
    List<EventInterval> events = ImmutableList.of(closed);
    long timeUntilNow =
        AbstractConsoleEventBusListener.getWorkingTimeFromLastStartUntilNow(events, 600);
    assertEquals("Time should be -1 since there's no ongoing events", -1L, timeUntilNow);
  }

  @Test
  public void testGetWorkingTimeFromLastStartUntilNowIsUntilNowForOpenPairs() {
    // Test overlapping ongoing events do not get measured twice
    EventInterval ongoing1 = EventInterval.of(OptionalLong.of(100), OptionalLong.empty());
    EventInterval ongoing2 = EventInterval.of(OptionalLong.of(200), OptionalLong.empty());
    long timeUntilNow =
        AbstractConsoleEventBusListener.getWorkingTimeFromLastStartUntilNow(
            ImmutableList.of(ongoing1, ongoing2), 300);
    assertEquals("Time should be counted since the latest ongoing event", 100L, timeUntilNow);

    // Test completed events are correctly accounted when getting ongoing time
    // If there are completed events, we don't want to overcount the time spent,
    // so ongoing time is always calculated from the last timestamp in the set.
    EventInterval closed = EventInterval.proxy(300, 400);
    timeUntilNow =
        AbstractConsoleEventBusListener.getWorkingTimeFromLastStartUntilNow(
            ImmutableList.of(ongoing1, closed), 600);
    assertEquals("Time should only be counted from the last closed event", 200L, timeUntilNow);

    // Test that finished-only events do not count as ongoing
    EventInterval finishOnly = EventInterval.of(OptionalLong.empty(), OptionalLong.of(100));
    timeUntilNow =
        AbstractConsoleEventBusListener.getWorkingTimeFromLastStartUntilNow(
            ImmutableList.of(finishOnly), 600);
    assertEquals("Finished events without start are not ongoing", -1L, timeUntilNow);
  }

  @Test
  public void testGetTotalCompletedTimeFromEventIntervals() {
    // Test events with a gap in between do not count the gap
    EventInterval zeroToOneHundred = EventInterval.proxy(0, 100);
    EventInterval oneToThreeHundred = EventInterval.proxy(100, 300);
    EventInterval twoToThreeHundred = EventInterval.proxy(200, 300);

    long timeElapsed =
        AbstractConsoleEventBusListener.getTotalCompletedTimeFromEventIntervals(
            ImmutableList.of(zeroToOneHundred, twoToThreeHundred));
    assertEquals("We should not add up time when there are no spanning events", 200L, timeElapsed);

    // Test overlapping events do not double-count
    timeElapsed =
        AbstractConsoleEventBusListener.getTotalCompletedTimeFromEventIntervals(
            ImmutableList.of(oneToThreeHundred, twoToThreeHundred));
    assertEquals("We should not double count when two event pairs overlap", 200L, timeElapsed);
  }
}
