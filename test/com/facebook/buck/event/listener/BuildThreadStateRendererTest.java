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
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDurationTracker;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.ClockDuration;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class BuildThreadStateRendererTest {

  private static final Ansi ANSI = Ansi.withoutTty();
  private static final Function<Long, String> FORMAT_TIME_FUNCTION =
      timeMs -> String.format(Locale.US, "%.1fs", timeMs / 1000.0);
  private static final SourcePathResolver PATH_RESOLVER =
      new SourcePathResolver(
          new SourcePathRuleFinder(
              new BuildRuleResolver(
                  TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
  private static final BuildTarget TARGET1 = BuildTargetFactory.newInstance("//:target1");
  private static final BuildTarget TARGET2 = BuildTargetFactory.newInstance("//:target2");
  private static final BuildTarget TARGET3 = BuildTargetFactory.newInstance("//:target3");
  private static final BuildTarget TARGET4 = BuildTargetFactory.newInstance("//:target4");
  private static final BuildRule RULE1 = createFakeRule(TARGET1);
  private static final BuildRule RULE2 = createFakeRule(TARGET2);
  private static final BuildRule RULE3 = createFakeRule(TARGET3);
  private static final BuildRule RULE4 = createFakeRule(TARGET4);

  @Test
  public void emptyInput() {
    BuildThreadStateRenderer renderer = createRenderer(2100, ImmutableMap.of(), ImmutableMap.of());
    assertThat(renderLines(renderer, true), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderLines(renderer, false), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderShortStatus(renderer, true), is(equalTo(ImmutableList.<String>of())));
    assertThat(renderShortStatus(renderer, false), is(equalTo(ImmutableList.<String>of())));
  }

  @Test
  public void commonCase() {
    BuildThreadStateRenderer renderer =
        createRenderer(
            4200,
            ImmutableMap.of(
                1L, createRuleBeginningEventOptional(1, 1200, 1400, RULE2),
                3L, createRuleBeginningEventOptional(3, 2300, 700, RULE3),
                4L, createRuleBeginningEventOptional(4, 1100, 200, RULE1),
                5L, Optional.empty(),
                8L, createRuleBeginningEventOptional(6, 3000, 0, RULE4)),
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
                    " |=> //:target2...  4.4s (running step A[2.7s])",
                    " |=> //:target1...  3.3s (checking_cache)",
                    " |=> //:target3...  2.6s (checking_cache)",
                    " |=> //:target4...  1.2s (running step B[0.5s])",
                    " |=> IDLE"))));
    assertThat(
        renderLines(renderer, false),
        is(
            equalTo(
                ImmutableList.of(
                    " |=> //:target2...  4.4s (running step A[2.7s])",
                    " |=> //:target3...  2.6s (checking_cache)",
                    " |=> //:target1...  3.3s (checking_cache)",
                    " |=> IDLE",
                    " |=> //:target4...  1.2s (running step B[0.5s])"))));
    assertThat(
        renderShortStatus(renderer, true),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[:]", "[ ]"))));
    assertThat(
        renderShortStatus(renderer, false),
        is(equalTo(ImmutableList.of("[:]", "[:]", "[:]", "[ ]", "[:]"))));
  }

  @Test
  public void withMissingInformation() {
    // SuperConsoleEventBusListener stores the data it passes to the renderer in a map that might
    // be concurrently modified from other threads. It is important that the renderer can handle
    // data containing inconsistencies.
    BuildThreadStateRenderer renderer =
        createRenderer(
            4200,
            ImmutableMap.of(
                3L, createRuleBeginningEventOptional(3, 2300, 700, RULE3),
                5L, Optional.empty(),
                8L, createRuleBeginningEventOptional(6, 3000, 0, RULE4)),
            ImmutableMap.of(
                1L, createStepStartedEventOptional(1, 1500, "step A"),
                4L, Optional.empty(),
                5L, Optional.empty(),
                8L, createStepStartedEventOptional(1, 3700, "step B")));
    assertThat(
        renderLines(renderer, true),
        is(
            equalTo(
                ImmutableList.of(
                    // one missing build rule - no output
                    " |=> //:target3...  2.6s (checking_cache)", // missing step information
                    " |=> //:target4...  1.2s (running step B[0.5s])",
                    " |=> IDLE")))); // missing accumulated time - show as IDLE
    assertThat(
        renderShortStatus(renderer, true), is(equalTo(ImmutableList.of("[:]", "[:]", "[ ]"))));
  }

  private static BuildRule createFakeRule(BuildTarget target) {
    return new FakeBuildRule(target, PATH_RESOLVER, ImmutableSortedSet.of());
  }

  private static Optional<? extends BuildRuleEvent.BeginningBuildRuleEvent>
      createRuleBeginningEventOptional(
          long threadId, long timeMs, long durationMs, BuildRule rule) {
    BuildRuleDurationTracker durationTracker = new BuildRuleDurationTracker();
    durationTracker.setDuration(rule, new ClockDuration(durationMs, 0, 0));
    RuleKey ruleKey = new RuleKey(HashCode.fromString("aa"));
    return Optional.of(
        TestEventConfigurator.configureTestEventAtTime(
            BuildRuleEvent.resumed(
                rule,
                durationTracker,
                new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), ruleKey))),
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

  private BuildThreadStateRenderer createRenderer(
      long timeMs,
      Map<Long, Optional<? extends BuildRuleEvent.BeginningBuildRuleEvent>> buildEvents,
      Map<Long, Optional<? extends LeafEvent>> runningSteps) {
    return new BuildThreadStateRenderer(
        ANSI,
        FORMAT_TIME_FUNCTION,
        timeMs,
        runningSteps,
        new BuildRuleThreadTracker(buildEvents, ImmutableMap.of()));
  }

  private ImmutableList<String> renderLines(BuildThreadStateRenderer renderer, boolean sortByTime) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    StringBuilder lineBuilder = new StringBuilder();
    for (long threadId : renderer.getSortedExecutorIds(sortByTime)) {
      lineBuilder.delete(0, lineBuilder.length());
      lines.add(renderer.renderStatusLine(threadId, lineBuilder));
    }
    return lines.build();
  }

  private ImmutableList<String> renderShortStatus(
      BuildThreadStateRenderer renderer, boolean sortByTime) {
    ImmutableList.Builder<String> status = ImmutableList.builder();
    for (long threadId : renderer.getSortedExecutorIds(sortByTime)) {
      status.add(renderer.renderShortStatus(threadId));
    }
    return status.build();
  }
}
