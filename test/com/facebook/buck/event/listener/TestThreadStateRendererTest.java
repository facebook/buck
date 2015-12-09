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

package com.facebook.buck.event.listener;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.TestEventConfigerator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TestThreadStateRendererTest {

  private static final Ansi ANSI = Ansi.withoutTty();
  private static final Function<Long, String> FORMAT_TIME_FUNCTION =
      new Function<Long, String>() {
        @Override
        public String apply(Long timeMs) {
          return String.format("%.1fs", timeMs / 1000.0);
        }
      };
  private static final BuildTarget TARGET1 = BuildTargetFactory.newInstance("//:target1");
  private static final BuildTarget TARGET2 = BuildTargetFactory.newInstance("//:target2");
  private static final BuildTarget TARGET3 = BuildTargetFactory.newInstance("//:target3");
  private static final BuildTarget TARGET4 = BuildTargetFactory.newInstance("//:target4");

  @Test
  public void emptyInput() {
    assertThat(
        createRendererAndRenderLines(
            2100,
            ImmutableMap.<Long, Optional<? extends TestRuleEvent>>of(),
            ImmutableMap.<Long, Optional<? extends TestSummaryEvent>>of(),
            ImmutableMap.<Long, Optional<? extends LeafEvent>>of(),
            ImmutableMap.<BuildTarget, AtomicLong>of()),
        is(equalTo(ImmutableList.<String>of())));
  }

  @Test
  public void commonCase() {
    assertThat(
        createRendererAndRenderLines(
            4200,
            ImmutableMap.of(
                1L, createTestStartedEventOptional(1, 1200, TARGET2),
                3L, createTestStartedEventOptional(3, 2300, TARGET3),
                4L, createTestStartedEventOptional(4, 1100, TARGET1),
                5L, Optional.<TestRuleEvent>absent(),
                8L, createTestStartedEventOptional(6, 3000, TARGET4)),
            ImmutableMap.of(
                1L, Optional.<TestSummaryEvent>absent(),
                3L, createTestSummaryEventOptional(1, 1600, "Test Case A", "Test A"),
                4L, Optional.<TestSummaryEvent>absent(),
                5L, Optional.<TestSummaryEvent>absent(),
                8L, createTestSummaryEventOptional(1, 3800, "Test Case B", "Test B")),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                3L, Optional.<LeafEvent>absent(),
                4L, Optional.<LeafEvent>absent(),
                5L, Optional.<LeafEvent>absent(),
                8L, createStepStartedEventOptional(1, 3700, "step B")),
            ImmutableMap.of(
                TARGET1, new AtomicLong(200),
                TARGET2, new AtomicLong(1400),
                TARGET3, new AtomicLong(700),
                TARGET4, new AtomicLong(0))),
        is(equalTo(
            ImmutableList.of(
                " |=> //:target2...  4.4s (running step A[2.7s])",
                " |=> //:target3...  2.6s (running Test A[2.6s])",
                " |=> //:target1...  3.3s",
                " |=> IDLE",
                " |=> //:target4...  1.2s (running Test B[0.4s])"))));
  }

  @Test
  public void withMissingInformation() {
    // SuperConsoleEventBusListener stores the data it passes to the renderer in a map that might
    // be concurrently modified from other threads. It is important that the renderer can handle
    // data containing inconsistencies.
    assertThat(
        createRendererAndRenderLines(
            4200,
            ImmutableMap.of(
                3L, createTestStartedEventOptional(3, 2300, TARGET3),
                4L, createTestStartedEventOptional(4, 1100, TARGET1),
                5L, Optional.<TestRuleEvent>absent(),
                8L, createTestStartedEventOptional(6, 3000, TARGET4)),
            ImmutableMap.<Long, Optional<? extends TestSummaryEvent>>of(
                1L, Optional.<TestSummaryEvent>absent(),
                4L, Optional.<TestSummaryEvent>absent(),
                5L, Optional.<TestSummaryEvent>absent()),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                3L, Optional.<LeafEvent>absent(),
                5L, Optional.<LeafEvent>absent()),
            ImmutableMap.of(
                TARGET1, new AtomicLong(200),
                TARGET2, new AtomicLong(1400),
                TARGET3, new AtomicLong(700))),
        is(equalTo(
            ImmutableList.of(
                // missing test rule - no output
                " |=> //:target3...  2.6s", // missing step information
                " |=> //:target1...  3.3s", // missing test summary
                " |=> IDLE",
                " |=> IDLE")))); // missing accumulated time - show as IDLE
  }

  private static Optional<? extends TestRuleEvent> createTestStartedEventOptional(
      long threadId,
      long timeMs,
      BuildTarget buildTarget) {
    return Optional.of(
        TestEventConfigerator.configureTestEventAtTime(
            TestRuleEvent.started(buildTarget),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private static Optional<? extends TestSummaryEvent> createTestSummaryEventOptional(
      long threadId,
      long timeMs,
      String testCaseName,
      String testName) {
    return Optional.of(
        TestEventConfigerator.configureTestEventAtTime(
            TestSummaryEvent.started(UUID.randomUUID(), testCaseName, testName),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private static Optional<? extends LeafEvent> createStepStartedEventOptional(
      long threadId,
      long timeMs,
      String name) {
    return Optional.of(
        TestEventConfigerator.configureTestEventAtTime(
            StepEvent.started(name, name + " description", UUID.randomUUID()),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private ImmutableList<String> createRendererAndRenderLines(
      long timeMs,
      Map<Long, Optional<? extends TestRuleEvent>> testEvents,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummaries,
      Map<Long, Optional<? extends LeafEvent>> runningSteps,
      Map<BuildTarget, AtomicLong> accumulatedTimes) {
    TestThreadStateRenderer renderer = new TestThreadStateRenderer(
        ANSI,
        FORMAT_TIME_FUNCTION,
        timeMs,
        testEvents,
        testSummaries,
        runningSteps,
        accumulatedTimes);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    StringBuilder lineBuilder = new StringBuilder();
    for (long threadId : renderer.getSortedThreadIds()) {
      lines.add(renderer.renderStatusLine(threadId, lineBuilder));
    }
    return lines.build();
  }

}
