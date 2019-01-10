/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.event.listener.stats.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.parser.ParseEvent.Finished;
import com.facebook.buck.parser.ParseEvent.Started;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.junit.Test;

public class ParseStatsTrackerTest {
  @Test
  public void testSimpleParse() {
    ParseStatsTracker tracker = new ParseStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    EventInterval interval = tracker.getInterval();

    assertFalse(interval.isStarted());

    BuildId buildId = new BuildId();
    Started started =
        ParseEvent.started(ImmutableList.of(BuildTargetFactory.newInstance("//:target")));
    started.configure(100, 0, 0, 0, buildId);
    eventBus.postWithoutConfiguring(started);

    interval = tracker.getInterval();
    assertEquals(100, interval.getStart().getAsLong());
    assertTrue(interval.isStarted());
    assertTrue(interval.isOngoing());

    Finished finished = ParseEvent.finished(started, 100, Optional.empty());
    finished.configure(200, 0, 0, 0, buildId);
    eventBus.postWithoutConfiguring(finished);

    interval = tracker.getInterval();
    assertTrue(interval.isComplete());
    assertEquals(100, interval.getStart().getAsLong());
    assertEquals(200, interval.getFinish().getAsLong());
    assertEquals(100, interval.getElapsedTimeMs());
  }

  @Test
  public void testMultiParse() {
    ParseStatsTracker tracker = new ParseStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    EventInterval interval = tracker.getInterval();

    assertFalse(interval.isStarted());

    BuildId buildId = new BuildId();
    Started startedRoot =
        ParseEvent.started(ImmutableList.of(BuildTargetFactory.newInstance("//:target")));
    startedRoot.configure(100, 0, 0, 0, buildId);
    eventBus.postWithoutConfiguring(startedRoot);

    interval = tracker.getInterval();
    assertEquals(100, interval.getStart().getAsLong());
    assertTrue(interval.isStarted());
    assertTrue(interval.isOngoing());

    Started startedSecondary = ParseEvent.started(ImmutableList.of());
    startedSecondary.configure(300, 0, 0, 1, buildId);
    eventBus.postWithoutConfiguring(startedSecondary);

    // The interval shouldn't have changed, it's begin should be the first parse started event.
    EventInterval newInterval = tracker.getInterval();
    assertEquals(interval, newInterval);

    Finished finishedRoot = ParseEvent.finished(startedRoot, 100, Optional.empty());
    finishedRoot.configure(500, 0, 0, 0, buildId);
    eventBus.postWithoutConfiguring(finishedRoot);

    // The interval still shouldn't have changed, one parse is still ongoing.
    newInterval = tracker.getInterval();
    assertEquals(interval, newInterval);

    Finished finishedSecondary = ParseEvent.finished(startedSecondary, 200, Optional.empty());
    finishedSecondary.configure(800, 0, 0, 0, buildId);
    eventBus.postWithoutConfiguring(finishedSecondary);

    interval = tracker.getInterval();
    assertTrue(interval.isComplete());
    assertEquals(100, interval.getStart().getAsLong());
    assertEquals(800, interval.getFinish().getAsLong());
    assertEquals(700, interval.getElapsedTimeMs());
  }
}
