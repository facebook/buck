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

package com.facebook.buck.event.listener;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.listener.PerfTimesEventListener.PerfTimesEvent;
import com.facebook.buck.log.PerfTimesStats;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDurationTracker;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.FakeExecutionEnvironment;
import com.facebook.buck.util.environment.Network;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.Subscribe;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class PertTimesEventListenerTest {

  private ExecutionEnvironment executionEnvironment;
  private SettableFakeClock fakeClock;
  private PerfTimesStats perfTimesStats;
  private BuildRuleDurationTracker durationTracker;

  @Before
  public void setUp() {
    fakeClock = new SettableFakeClock(Instant.parse("2017-10-12T12:13:14.123Z").toEpochMilli(), 0);
    executionEnvironment =
        FakeExecutionEnvironment.of(
            "hostname",
            "username",
            4,
            128,
            Platform.UNKNOWN,
            new Network(),
            Optional.empty(),
            ImmutableMap.of());
    durationTracker = new BuildRuleDurationTracker();
  }

  @Test
  public void testPerfTimes() {
    RuleKey ruleKey = new RuleKey("deadbeef");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//alice:wonderland");
    BuildRule buildRule = new FakeBuildRule(buildTarget, ImmutableSortedSet.of());
    FakeRuleKeyFactory ruleKeyFactory =
        new FakeRuleKeyFactory(ImmutableMap.of(buildTarget, ruleKey));

    RuleKey ruleKey2 = new RuleKey("deadbeef");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//mad:hatter");
    BuildRule buildRule2 = new FakeBuildRule(buildTarget2, ImmutableSortedSet.of());
    FakeRuleKeyFactory ruleKeyFactory2 =
        new FakeRuleKeyFactory(ImmutableMap.of(buildTarget2, ruleKey2));

    BuckEventBus eventBus = BuckEventBusForTests.newInstance(fakeClock);
    PerfTimesEventListener listener = new PerfTimesEventListener(eventBus, executionEnvironment);
    eventBus.register(listener);

    eventBus.register(
        new Object() {
          @Subscribe
          public void onPerfStatsSummary(PerfTimesEvent event) {
            perfTimesStats = event.getPerfTimesStats();
          }
        });

    fakeClock.setCurrentTimeMillis(100);
    BuildEvent.Started buildStarted = BuildEvent.started(ImmutableList.of());
    eventBus.post(buildStarted);

    fakeClock.setCurrentTimeMillis(500);
    BuckInitializationDurationEvent buckInit = new BuckInitializationDurationEvent(500);
    eventBus.post(buckInit);

    fakeClock.setCurrentTimeMillis(1000);
    ParseEvent.Started parseStarted = ParseEvent.started(ImmutableList.of(buildTarget));
    eventBus.post(parseStarted);

    fakeClock.setCurrentTimeMillis(2000);
    ParseEvent.Finished parseFinished = ParseEvent.finished(parseStarted, 23L, Optional.empty());
    eventBus.post(parseFinished);

    fakeClock.setCurrentTimeMillis(2500);
    ActionGraphEvent.Started actionGraphStarted = ActionGraphEvent.started();
    ActionGraphEvent.Finished actionGraphFinished =
        ActionGraphEvent.finished(actionGraphStarted, 1);
    eventBus.post(actionGraphFinished);

    // This adds 30ms of rule key computation time.
    fakeClock.setCurrentTimeMillis(3000);
    fakeClock.advanceTimeNanos(10000000);
    BuildRuleEvent.StartedRuleKeyCalc ruleKeyCalcStarted =
        BuildRuleEvent.ruleKeyCalculationStarted(buildRule, durationTracker);
    eventBus.post(ruleKeyCalcStarted);
    fakeClock.setCurrentTimeMillis(3100);
    fakeClock.advanceTimeNanos(30000000);
    BuildRuleEvent.FinishedRuleKeyCalc ruleKeyCalcFinished =
        BuildRuleEvent.ruleKeyCalculationFinished(ruleKeyCalcStarted, ruleKeyFactory);
    eventBus.post(ruleKeyCalcFinished);

    // This adds 70ms of rule key computation time.
    fakeClock.setCurrentTimeMillis(3000);
    fakeClock.advanceTimeNanos(10000000);
    BuildRuleEvent.StartedRuleKeyCalc ruleKeyCalcStarted2 =
        BuildRuleEvent.ruleKeyCalculationStarted(buildRule2, durationTracker);
    eventBus.post(ruleKeyCalcStarted2);
    fakeClock.setCurrentTimeMillis(3100);
    fakeClock.advanceTimeNanos(70000000);
    BuildRuleEvent.FinishedRuleKeyCalc ruleKeyCalcFinished2 =
        BuildRuleEvent.ruleKeyCalculationFinished(ruleKeyCalcStarted2, ruleKeyFactory2);
    eventBus.post(ruleKeyCalcFinished2);

    fakeClock.setCurrentTimeMillis(4000);
    HttpArtifactCacheEvent.Started fetchStarted =
        HttpArtifactCacheEvent.newFetchStartedEvent(ruleKey);
    eventBus.post(fetchStarted);

    fakeClock.setCurrentTimeMillis(4500);
    BuildRuleEvent.WillBuildLocally willBuildLocallyStarted =
        BuildRuleEvent.willBuildLocally(buildRule);
    eventBus.post(willBuildLocallyStarted);

    fakeClock.setCurrentTimeMillis(8000);
    BuildEvent.Finished buildFinished = BuildEvent.finished(buildStarted, ExitCode.SUCCESS);
    eventBus.post(buildFinished);

    fakeClock.setCurrentTimeMillis(8050);
    InstallEvent.Started installStarted = InstallEvent.started(buildTarget);
    eventBus.post(installStarted);

    fakeClock.setCurrentTimeMillis(9000);
    InstallEvent.Finished installFinished =
        InstallEvent.finished(installStarted, true, Optional.empty(), Optional.empty());
    eventBus.post(installFinished);

    assertThat(perfTimesStats, Matchers.notNullValue());
    assertEquals(new Long(500L), perfTimesStats.getInitTimeMs());
    assertEquals(new Long(500L), perfTimesStats.getProcessingTimeMs());
    assertEquals(new Long(1000L), perfTimesStats.getParseTimeMs());
    assertEquals(new Long(500L), perfTimesStats.getActionGraphTimeMs());
    assertEquals(new Long(1500L), perfTimesStats.getRulekeyTimeMs());
    assertEquals(new Long(100L), perfTimesStats.getTotalRulekeyTimeMs());
    assertEquals(new Long(500L), perfTimesStats.getFetchTimeMs());
    assertEquals(new Long(3500L), perfTimesStats.getBuildTimeMs());
    assertEquals(new Long(1000L), perfTimesStats.getInstallTimeMs());
  }
}
