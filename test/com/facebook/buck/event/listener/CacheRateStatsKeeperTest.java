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

import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.distributed.thrift.CacheRateStats;
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.rules.FakeBuildRule;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CacheRateStatsKeeperTest {
  @Test
  public void getStatsWithNoEvents() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(0));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(0));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(0));
  }

  BuildRuleEvent.Finished finishedEvent(CacheResult cacheResult) {
    BuildRule rule = new FakeBuildRule("//fake:rule");
    BuildRuleDurationTracker durationTracker = new BuildRuleDurationTracker();
    BuildRuleEvent.Started started =
        TestEventConfigurator.configureTestEvent(BuildRuleEvent.started(rule, durationTracker));
    return BuildRuleEvent.finished(
        started,
        BuildRuleKeys.of(new RuleKey("aa")),
        BuildRuleStatus.SUCCESS,
        cacheResult,
        Optional.empty(),
        Optional.empty(),
        UploadToCacheResultType.UNCACHEABLE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  public void cacheMissHitWithNoCount() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));
    cacheRateStatsKeeper.buildRuleFinished(
        finishedEvent(CacheResult.hit("dir", ArtifactCacheMode.dir)));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(1));
    assertThat(stats.getCacheMissRate(), Matchers.is(50.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(1));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(2));
  }

  @Test
  public void cacheHit() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(
        finishedEvent(CacheResult.hit("dir", ArtifactCacheMode.dir)));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(0));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(1));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(1));
  }

  @Test
  public void cacheMiss() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 5));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));
    cacheRateStatsKeeper.buildRuleFinished(
        finishedEvent(CacheResult.hit("dir", ArtifactCacheMode.dir)));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));
    cacheRateStatsKeeper.buildRuleFinished(
        finishedEvent(CacheResult.hit("dir", ArtifactCacheMode.dir)));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(3));
    assertThat(stats.getCacheMissRate(), Matchers.is(60.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(2));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(5));
  }

  @Test
  public void cacheError() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(
        finishedEvent(CacheResult.error("dir", ArtifactCacheMode.dir, "error")));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(1));
    assertThat(stats.getCacheErrorRate(), Matchers.is(100.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(0));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(0));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(1));
  }

  @Test
  public void cacheIgnored() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.ignored()));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(0));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(0));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(1));
  }

  @Test
  public void unexpectedStampedeSlaveCacheMisses() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.recordUnexpectedCacheMisses(6);
    cacheRateStatsKeeper.recordUnexpectedCacheMisses(7);

    CacheRateStats serializableStats = cacheRateStatsKeeper.getSerializableStats();

    assertThat(serializableStats.getUnexpectedCacheMissesCount(), Matchers.is(13));
    assertThat(serializableStats.getCacheErrorsCount(), Matchers.is(0));
    assertThat(serializableStats.getCacheHitsCount(), Matchers.is(0));
    assertThat(serializableStats.getCacheMissesCount(), Matchers.is(0));
    assertThat(serializableStats.getTotalRulesCount(), Matchers.is(0));
    assertThat(serializableStats.getUpdatedRulesCount(), Matchers.is(0));
    assertThat(serializableStats.getCacheIgnoresCount(), Matchers.is(0));
    assertThat(serializableStats.getCacheLocalKeyUnchangedHitsCount(), Matchers.is(0));
  }

  @Test
  public void cacheLocalUnchangedHitDoesntAffectCounters() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(ImmutableSet.of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.localKeyUnchangedHit()));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(0));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(0));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(0));
  }
}
