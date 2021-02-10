/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.util.types.Pair;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Test;

/** Ensures that {@link CacheMissBuildTimeListener} runs properly. */
public class CacheMissBuildTimeListenerTest {
  BuckEventBus buckEventBus;
  CacheMissBuildTimeListener cacheMissBuildTimeListener;
  RuleKeyCheckListenerConfig config;
  FakeBuckEventListener fakeBuckEventListener;
  FakeBuildRule buildRule;
  RuleKey buildRuleKey;

  @Before
  public void setUp() {
    buckEventBus = BuckEventBusForTests.newInstance();
    buildRule = new FakeBuildRule(BuildTargetFactory.newInstance("//repo/path:module"));
    buildRuleKey = new RuleKey("1234");
    config =
        FakeBuckConfig.builder()
            .setSections(
                "[rulekey_check]",
                "divergence_warning_message = //repo/.* => warning message",
                "divergence_warning_threshold_in_sec = 60")
            .build()
            .getView(RuleKeyCheckListenerConfig.class);
    cacheMissBuildTimeListener = new CacheMissBuildTimeListener(buckEventBus, config);
    fakeBuckEventListener = new FakeBuckEventListener();
    buckEventBus.register(cacheMissBuildTimeListener);
    buckEventBus.register(fakeBuckEventListener);
  }

  @Test
  public void testNoCacheMiss() {
    BuildRuleEvent.Finished finished =
        BuildRuleEvent.finished(
            BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
            BuildRuleKeys.of(buildRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.skipped(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    buckEventBus.post(finished);

    assertEquals(1, fakeBuckEventListener.getEvents().size());
    assertEquals(finished, fakeBuckEventListener.getEvents().get(0));
  }

  @Test
  public void testCacheMiss() {
    BuildRuleEvent.Finished finished =
        BuildRuleEvent.finished(
            BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
            BuildRuleKeys.of(buildRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new Pair<>(0L, TimeUnit.SECONDS.toMillis(61))),
            Optional.empty());

    buckEventBus.post(finished);
    assertEquals(2, fakeBuckEventListener.getEvents().size());
    assertEquals(finished, fakeBuckEventListener.getEvents().get(0));
    assertThat(fakeBuckEventListener.getEvents().get(1), is(instanceOf(ConsoleEvent.class)));
    ConsoleEvent consoleEvent = (ConsoleEvent) fakeBuckEventListener.getEvents().get(1);
    assertEquals(Level.WARNING, consoleEvent.getLevel());
    assertEquals(
        consoleEvent.getMessage(),
        String.format("%s", config.getDivergenceWarningMessageMap().get("//repo/.*")));
  }

  @Test
  public void testCacheMissWarningFrequency() {
    BuildRuleEvent.Finished firstBuildRuleFinished =
        BuildRuleEvent.finished(
            BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
            BuildRuleKeys.of(buildRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new Pair<>(0L, TimeUnit.SECONDS.toMillis(61))),
            Optional.empty());

    BuildRuleEvent.Finished secondBuildRuleFinished =
        BuildRuleEvent.finished(
            BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
            BuildRuleKeys.of(buildRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new Pair<>(TimeUnit.SECONDS.toMillis(62), TimeUnit.SECONDS.toMillis(120))),
            Optional.empty());

    buckEventBus.post(firstBuildRuleFinished);
    buckEventBus.post(secondBuildRuleFinished);
    assertEquals(3, fakeBuckEventListener.getEvents().size());
    assertEquals(firstBuildRuleFinished, fakeBuckEventListener.getEvents().get(0));
    assertEquals(secondBuildRuleFinished, fakeBuckEventListener.getEvents().get(2));
    assertThat(fakeBuckEventListener.getEvents().get(1), is(instanceOf(ConsoleEvent.class)));
    ConsoleEvent consoleEvent = (ConsoleEvent) fakeBuckEventListener.getEvents().get(1);
    assertEquals(Level.WARNING, consoleEvent.getLevel());
    assertEquals(
        consoleEvent.getMessage(),
        String.format("%s", config.getDivergenceWarningMessageMap().get("//repo/.*")));
  }
}
