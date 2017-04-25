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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class DefaultRuleKeyCacheTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();

  @Test
  public void testGetReturnValue() {
    DefaultRuleKeyCache<String> cache = new DefaultRuleKeyCache<>();
    TestRule rule = new TestRule();
    assertThat(
        cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of())),
        Matchers.equalTo("result"));
  }

  @Test
  public void testCacheRule() {
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>();
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of()));
    assertTrue(cache.isCached(rule));
    cache.get(
        rule,
        r -> {
          throw new IllegalStateException();
        });
  }

  @Test
  public void testInvalidateInputToCachedRule() {
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>();
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of(input)));
    assertTrue(cache.isCached(rule));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(cache.isCached(rule));
  }

  @Test
  public void testInvalidateTransitiveInputToCachedRule() {
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    TestRule dep = new TestRule();
    cache.get(dep, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of(input)));
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(dep), ImmutableList.of()));
    assertTrue(cache.isCached(rule));
    assertTrue(cache.isCached(dep));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(cache.isCached(rule));
    assertFalse(cache.isCached(dep));
  }

  @Test
  public void testInvalidateInputToCachedRuleDoesNotInvalidateDependency() {
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    TestRule dep = new TestRule();
    cache.get(dep, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of()));
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(dep), ImmutableList.of(input)));
    assertTrue(cache.isCached(rule));
    assertTrue(cache.isCached(dep));
    cache.invalidateInputs(ImmutableList.of(input));
    assertFalse(cache.isCached(rule));
    assertTrue(cache.isCached(dep));
  }

  @Test
  public void testHitMissStats() {
    DefaultRuleKeyCache<String> cache = new DefaultRuleKeyCache<>();
    TestRule rule = new TestRule();
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    cache.get(rule, r -> new RuleKeyResult<>("result", ImmutableList.of(), ImmutableList.of()));
    assertThat(cache.getStats().missCount(), Matchers.equalTo(1L));
    assertThat(cache.getStats().hitCount(), Matchers.equalTo(2L));
  }

  @Test
  public void testEvictionStats() {
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>();
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of(input)));
    cache.invalidateInputs(ImmutableList.of(input));
    assertThat(cache.getStats().evictionCount(), Matchers.equalTo(1L));
  }

  @Test
  public void testLoadTime() {
    Clock clock = new IncrementingFakeClock();
    DefaultRuleKeyCache<Void> cache = new DefaultRuleKeyCache<>(clock);
    TestRule rule = new TestRule();
    RuleKeyInput input = RuleKeyInput.of(FILESYSTEM, FILESYSTEM.getPath("input"));
    cache.get(rule, r -> new RuleKeyResult<>(null, ImmutableList.of(), ImmutableList.of(input)));
    cache.invalidateInputs(ImmutableList.of(input));
    assertThat(cache.getStats().totalLoadTime(), Matchers.equalTo(1L));
  }

  private static class TestRule extends NoopBuildRule {

    private TestRule() {
      super(new FakeBuildRuleParamsBuilder("//:rule").build());
    }
  }
}
