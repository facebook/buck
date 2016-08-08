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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.easymock.EasyMock;
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
    return BuildRuleEvent.finished(
            EasyMock.createMock(BuildRule.class),
            BuildRuleKeys.of(new RuleKey("aa")),
            BuildRuleStatus.SUCCESS,
            cacheResult,
            Optional.<BuildRuleSuccessType>absent(),
            Optional.<HashCode>absent(),
            Optional.<Long>absent());
  }

  @Test
  public void cacheMissHitWithNoCount() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.hit("dir")));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(1));
    assertThat(stats.getCacheMissRate(), Matchers.is(0.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(1));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(2));
  }

  @Test
  public void cacheHit() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(
            ImmutableSet.<BuildTarget>of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.hit("dir")));

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
        BuildEvent.RuleCountCalculated.ruleCountCalculated(
            ImmutableSet.<BuildTarget>of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.miss()));

    CacheRateStatsKeeper.CacheRateStatsUpdateEvent stats = cacheRateStatsKeeper.getStats();

    assertThat(stats.getCacheErrorCount(), Matchers.is(0));
    assertThat(stats.getCacheErrorRate(), Matchers.is(0.0));
    assertThat(stats.getCacheMissCount(), Matchers.is(1));
    assertThat(stats.getCacheMissRate(), Matchers.is(25.0));
    assertThat(stats.getCacheHitCount(), Matchers.is(0));
    assertThat(stats.getUpdatedRulesCount(), Matchers.is(1));
  }

  @Test
  public void cacheError() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(
            ImmutableSet.<BuildTarget>of(), 4));
    cacheRateStatsKeeper.buildRuleFinished(finishedEvent(CacheResult.error("dir", "error")));

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
        BuildEvent.RuleCountCalculated.ruleCountCalculated(
            ImmutableSet.<BuildTarget>of(), 4));
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
  public void cacheLocalUnchangedHitDoesntAffectCounters() {
    CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();
    cacheRateStatsKeeper.ruleCountCalculated(
        BuildEvent.RuleCountCalculated.ruleCountCalculated(
            ImmutableSet.<BuildTarget>of(), 4));
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
