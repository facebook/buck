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

package com.facebook.buck.rules;

import static com.facebook.buck.rules.BuildRuleSuccessType.BUILT_LOCALLY;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class FakeBuildEngineTest {

  @Test
  public void buildRuleFutureHasResult() throws Exception {
    BuildTarget fakeBuildTarget = BuildTarget.builder("//foo", "bar").build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget, pathResolver);
    BuildResult fakeBuildResult = new BuildResult(fakeBuildRule, BUILT_LOCALLY, CacheResult.skip());
    FakeBuildEngine fakeEngine = new FakeBuildEngine(
        ImmutableMap.of(fakeBuildTarget, fakeBuildResult),
        ImmutableMap.of(fakeBuildTarget, new RuleKey("00")));
    assertThat(
        fakeEngine.build(FakeBuildContext.NOOP_CONTEXT, fakeBuildRule).get(),
        equalTo(fakeBuildResult));
  }

  @Test
  public void buildRuleResultIsPresent() throws Exception {
    BuildTarget fakeBuildTarget = BuildTarget.builder("//foo", "bar").build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget, pathResolver);
    BuildResult fakeBuildResult = new BuildResult(fakeBuildRule, BUILT_LOCALLY, CacheResult.skip());
    FakeBuildEngine fakeEngine = new FakeBuildEngine(
        ImmutableMap.of(fakeBuildTarget, fakeBuildResult),
        ImmutableMap.of(fakeBuildTarget, new RuleKey("00")));
    assertThat(
        fakeEngine.getBuildRuleResult(fakeBuildTarget),
        equalTo(fakeBuildResult));
  }

  @Test
  public void buildRuleIsBuilt() throws Exception {
    BuildTarget fakeBuildTarget = BuildTarget.builder("//foo", "bar").build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget, pathResolver);
    BuildResult fakeBuildResult = new BuildResult(fakeBuildRule, BUILT_LOCALLY, CacheResult.skip());
    FakeBuildEngine fakeEngine = new FakeBuildEngine(
        ImmutableMap.of(fakeBuildTarget, fakeBuildResult),
        ImmutableMap.of(fakeBuildTarget, new RuleKey("00")));
    assertThat(
        fakeEngine.isRuleBuilt(fakeBuildTarget),
        is(true));
  }

  @Test
  public void unbuiltRuleIsNotBuilt() throws Exception {
    BuildTarget fakeBuildTarget = BuildTarget.builder("//foo", "bar").build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget, pathResolver);
    BuildResult fakeBuildResult = new BuildResult(fakeBuildRule, BUILT_LOCALLY, CacheResult.skip());
    FakeBuildEngine fakeEngine = new FakeBuildEngine(
        ImmutableMap.of(fakeBuildTarget, fakeBuildResult),
        ImmutableMap.of(fakeBuildTarget, new RuleKey("00")));
    BuildTarget anotherFakeBuildTarget = BuildTarget.builder("//foo", "baz").build();
    assertThat(
        fakeEngine.isRuleBuilt(anotherFakeBuildTarget),
        is(false));
  }

  @Test
  public void ruleKeyIsPresent() throws Exception {
    BuildTarget fakeBuildTarget = BuildTarget.builder("//foo", "bar").build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget, pathResolver);
    BuildResult fakeBuildResult = new BuildResult(fakeBuildRule, BUILT_LOCALLY, CacheResult.skip());
    FakeBuildEngine fakeEngine = new FakeBuildEngine(
        ImmutableMap.of(fakeBuildTarget, fakeBuildResult),
        ImmutableMap.of(fakeBuildTarget, new RuleKey("00")));
    assertThat(
        fakeEngine.getRuleKey(fakeBuildTarget),
        equalTo(new RuleKey("00")));
  }
}
