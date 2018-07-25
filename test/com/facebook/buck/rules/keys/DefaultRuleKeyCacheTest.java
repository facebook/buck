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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Test;

public class DefaultRuleKeyCacheTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();

  @Test
  public void testGetReturnValue() {
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(
            new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker());
    TestRule rule = new TestRule();
    assertThat(
        cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of())),
        Matchers.equalTo("result"));
  }

  @Test
  public void testCacheRule() {
    DefaultRuleKeyCache<String> internalCache = new DefaultRuleKeyCache<>();
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(internalCache, new InstrumentingCacheStatsTracker());
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of()));
    assertTrue(internalCache.isCached(rule));
    cache.get(
        rule,
        r -> {
          throw new IllegalStateException();
        });
  }

  @Test
  public void testInvalidateInputToCachedRule() {
    DefaultRuleKeyCache<String> internalCache = new DefaultRuleKeyCache<>();
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(internalCache, new InstrumentingCacheStatsTracker());
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of(input)));
    assertTrue(internalCache.isCached(rule));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(internalCache.isCached(rule));
  }

  @Test
  public void testInvalidateTransitiveInputToCachedRule() {
    DefaultRuleKeyCache<String> internalCache = new DefaultRuleKeyCache<>();
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(internalCache, new InstrumentingCacheStatsTracker());
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    TestRule dep = new TestRule();
    cache.get(dep, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of(input)));
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(dep), ImmutableList.of()));

    assertTrue(internalCache.isCached(rule));
    assertTrue(internalCache.isCached(dep));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(internalCache.isCached(rule));
    assertFalse(internalCache.isCached(dep));
  }

  @Test
  public void testInvalidateInputToCachedRuleDoesNotInvalidateDependency() {
    DefaultRuleKeyCache<String> internalCache = new DefaultRuleKeyCache<>();
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(internalCache, new InstrumentingCacheStatsTracker());
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    TestRule dep = new TestRule();
    cache.get(dep, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of()));
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(dep), ImmutableList.of(input)));
    assertTrue(internalCache.isCached(rule));
    assertTrue(internalCache.isCached(dep));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(internalCache.isCached(rule));
    assertTrue(internalCache.isCached(dep));
  }

  @Test
  public void invalidatingDiamondDependencyWorksCorrectly() {
    // A -> B
    // |    |
    // v    v
    // C -> D

    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    TestRule ruleA = new TestRule();
    TestRule ruleB = new TestRule();
    TestRule ruleC = new TestRule();
    TestRule ruleD = new TestRule();

    DefaultRuleKeyCache<String> internalCache = new DefaultRuleKeyCache<>();
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(internalCache, new InstrumentingCacheStatsTracker());
    cache.get(ruleA, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of(input)));
    cache.get(ruleB, r -> new RuleKeyResult<>("", ImmutableList.of(ruleA), ImmutableList.of()));
    cache.get(ruleC, r -> new RuleKeyResult<>("", ImmutableList.of(ruleA), ImmutableList.of()));
    cache.get(
        ruleD, r -> new RuleKeyResult<>("", ImmutableList.of(ruleB, ruleC), ImmutableList.of()));
    assertTrue(internalCache.isCached(ruleD));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(internalCache.isCached(ruleD));
  }

  @Test
  public void testHitMissStats() {
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(
            new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker());
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    assertThat(cache.getStats().getMissCount().get(), Matchers.equalTo(1L));
    assertThat(cache.getStats().getHitCount().get(), Matchers.equalTo(2L));
  }

  @Test
  public void testEvictionStats() {
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(
            new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker());
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of(input)));
    cache.invalidateInputs(ImmutableList.of(input));
    assertThat(cache.getStats().getEvictionCount().get(), Matchers.equalTo(1L));
  }

  @Test
  public void testLoadTime() {
    Clock clock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    TrackedRuleKeyCache<String> cache =
        new TrackedRuleKeyCache<>(
            new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker(clock));
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>("", ImmutableList.of(), ImmutableList.of(input)));
    cache.invalidateInputs(ImmutableList.of(input));
    assertThat(cache.getStats().getTotalLoadTime().get(), Matchers.equalTo(1L));
  }

  private static class TestRule extends NoopBuildRuleWithDeclaredAndExtraDeps {

    private TestRule() {
      super(
          BuildTargetFactory.newInstance("//:rule"),
          new FakeProjectFilesystem(),
          TestBuildRuleParams.create());
    }
  }
}
