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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import org.junit.Test;

public class TestThreadStateRendererTest {

  private static final Ansi ANSI = Ansi.withoutTty();
  private static final Function<Long, String> FORMAT_TIME_FUNCTION =
      timeMs -> String.format(Locale.US, "%.1fs", timeMs / 1000.0);
  private static final BuildTarget TARGET1 = BuildTargetFactory.newInstance("//:target1");
  private static final BuildTarget TARGET2 = BuildTargetFactory.newInstance("//:target2");
  private static final BuildTarget TARGET3 = BuildTargetFactory.newInstance("//:target3");
  private static final BuildTarget TARGET4 = BuildTargetFactory.newInstance("//:target4");

  @Test
  public void emptyInput() {
    TestThreadStateRenderer renderer =
        createRenderer(
            2100, ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    assertThat(renderLines(renderer, true), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderLines(renderer, false), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderShortStatus(renderer, true), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderShortStatus(renderer, false), is(equalTo(ImmutableList.<String>of())));
  }

  @Test
  public void commonCase() {
    TestThreadStateRenderer renderer =
        createRenderer(
            4200,
            ImmutableMap.of(
                1L, createTestStartedEventOptional(1, 1000, TARGET2),
                3L, createTestStartedEventOptional(3, 2300, TARGET3),
                4L, createTestStartedEventOptional(4, 1100, TARGET1),
                5L, Optional.empty(),
                8L, createTestStartedEventOptional(6, 3000, TARGET4)),
            ImmutableMap.of(
                1L, Optional.empty(),
                3L, createTestSummaryEventOptional(1, 1600, "Test Case A", "Test A"),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, createTestSummaryEventOptional(1, 3800, "Test Case B", "Test B")),
            ImmutableMap.of(),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                3L, Optional.empty(),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, createStepStartedEventOptional(1, 3700, "step B")));
    assertThat(
        renderLines(renderer, true),
        is(
            equalTo(
                ImmutableList.of(
                    " - //:target2... 3.2s (running step A[2.7s])",
                    " - //:target1... 3.1s",
                    " - //:target3... 1.9s (running Test A[2.6s])",
                    " - //:target4... 1.2s (running Test B[0.4s])",
                    " - IDLE"))));
    assertThat(
        renderLines(renderer, false),
        is(
            equalTo(
                ImmutableList.of(
                    " - //:target2... 3.2s (running step A[2.7s])",
                    " - //:target3... 1.9s (running Test A[2.6s])",
                    " - //:target1... 3.1s",
                    " - IDLE",
                    " - //:target4... 1.2s (running Test B[0.4s])"))));
    assertThat(
        renderShortStatus(renderer, true),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[:]", "[ ]"))));
    assertThat(
        renderShortStatus(renderer, false),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[ ]", "[:]"))));
  }

  @Test
  public void withTestStatusMessageEvents() {
    TestThreadStateRenderer renderer =
        createRenderer(
            4200,
            ImmutableMap.of(
                1L, createTestStartedEventOptional(1, 1000, TARGET2),
                3L, createTestStartedEventOptional(3, 2300, TARGET3),
                4L, createTestStartedEventOptional(4, 1100, TARGET1),
                5L, Optional.empty(),
                8L, createTestStartedEventOptional(6, 3000, TARGET4)),
            ImmutableMap.of(
                1L, Optional.empty(),
                3L, createTestSummaryEventOptional(1, 1600, "Test Case A", "Test A"),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, Optional.empty()),
            ImmutableMap.of(
                1L, Optional.empty(),
                3L, Optional.empty(),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, createTestStatusMessageEventOptional(1, 3800, "Installing Sim", Level.INFO)),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                3L, Optional.empty(),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, createStepStartedEventOptional(1, 3700, "step B")));
    assertThat(
        renderLines(renderer, true),
        is(
            equalTo(
                ImmutableList.of(
                    " - //:target2... 3.2s (running step A[2.7s])",
                    " - //:target1... 3.1s",
                    " - //:target3... 1.9s (running Test A[2.6s])",
                    " - //:target4... 1.2s (running Installing Sim[0.4s])",
                    " - IDLE"))));
    assertThat(
        renderShortStatus(renderer, true),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[:]", "[ ]"))));
  }

  @Test
  public void withMissingInformation() {
    // SuperConsoleEventBusListener stores the data it passes to the renderer in a map that might
    // be concurrently modified from other threads. It is important that the renderer can handle
    // data containing inconsistencies.
    TestThreadStateRenderer renderer =
        createRenderer(
            4200,
            ImmutableMap.of(
                3L, createTestStartedEventOptional(3, 2300, TARGET3),
                4L, createTestStartedEventOptional(4, 1100, TARGET1),
                5L, Optional.empty(),
                8L, createTestStartedEventOptional(6, 3000, TARGET4)),
            ImmutableMap.of(
                1L, Optional.empty(),
                4L, Optional.empty(),
                5L, Optional.empty()),
            ImmutableMap.of(),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                3L, Optional.empty(),
                5L, Optional.empty()));
    assertThat(
        renderLines(renderer, true),
        is(
            equalTo(
                ImmutableList.of(
                    // missing test rule - no output
                    " - //:target1... 3.1s", // missing test summary
                    " - //:target3... 1.9s", // missing step information
                    " - //:target4... 1.2s",
                    " - IDLE")))); // missing accumulated time - show as IDLE
    assertThat(
        renderShortStatus(renderer, true),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[ ]"))));
  }

  private static Optional<? extends TestRuleEvent> createTestStartedEventOptional(
      long threadId, long timeMs, BuildTarget buildTarget) {
    return Optional.of(
        TestEventConfigurator.configureTestEventAtTime(
            TestRuleEvent.started(buildTarget), timeMs, TimeUnit.MILLISECONDS, threadId));
  }

  private static Optional<? extends TestSummaryEvent> createTestSummaryEventOptional(
      long threadId, long timeMs, String testCaseName, String testName) {
    return Optional.of(
        TestEventConfigurator.configureTestEventAtTime(
            TestSummaryEvent.started(UUID.randomUUID(), testCaseName, testName),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private static Optional<? extends TestStatusMessageEvent> createTestStatusMessageEventOptional(
      long threadId, long timeMs, String message, Level level) {
    return Optional.of(
        TestEventConfigurator.configureTestEventAtTime(
            TestStatusMessageEvent.started(TestStatusMessage.of(message, level, timeMs)),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private static Optional<? extends LeafEvent> createStepStartedEventOptional(
      long threadId, long timeMs, String name) {
    return Optional.of(
        TestEventConfigurator.configureTestEventAtTime(
            StepEvent.started(name, name + " description", UUID.randomUUID()),
            timeMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }

  private TestThreadStateRenderer createRenderer(
      long timeMs,
      Map<Long, Optional<? extends TestRuleEvent>> testEvents,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummaries,
      Map<Long, Optional<? extends TestStatusMessageEvent>> testStatusMessages,
      Map<Long, Optional<? extends LeafEvent>> runningSteps) {
    return new TestThreadStateRenderer(
        ANSI,
        FORMAT_TIME_FUNCTION,
        timeMs,
        80, /* outputMaxColumns */
        testSummaries,
        testStatusMessages,
        runningSteps,
        new BuildRuleThreadTracker(ImmutableMap.of(), testEvents));
  }

  private ImmutableList<String> renderLines(TestThreadStateRenderer renderer, boolean sortByTime) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    StringBuilder lineBuilder = new StringBuilder();
    for (long threadId : renderer.getSortedExecutorIds(sortByTime)) {
      lineBuilder.delete(0, lineBuilder.length());
      lines.add(renderer.renderStatusLine(threadId, lineBuilder));
    }
    return lines.build();
  }

  private ImmutableList<String> renderShortStatus(
      TestThreadStateRenderer renderer, boolean sortByTime) {
    ImmutableList.Builder<String> status = ImmutableList.builder();
    for (long threadId : renderer.getSortedExecutorIds(sortByTime)) {
      status.add(renderer.renderShortStatus(threadId));
    }
    return status.build();
  }
}
