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

import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.BUILT_LOCALLY;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class FakeBuildEngineTest {

  @Test
  public void buildRuleFutureHasResult() throws Exception {
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:bar");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget);
    BuildResult fakeBuildResult =
        BuildResult.success(fakeBuildRule, BUILT_LOCALLY, CacheResult.miss());
    FakeBuildEngine fakeEngine =
        new FakeBuildEngine(ImmutableMap.of(fakeBuildTarget, fakeBuildResult));
    assertThat(
        fakeEngine
            .build(
                BuildEngineBuildContext.builder()
                    .setBuildContext(FakeBuildContext.NOOP_CONTEXT)
                    .setArtifactCache(new NoopArtifactCache())
                    .setBuildId(new BuildId())
                    .setClock(new DefaultClock())
                    .build(),
                TestExecutionContext.newInstance(),
                fakeBuildRule)
            .getResult()
            .get(),
        equalTo(fakeBuildResult));
  }

  @Test
  public void buildRuleResultIsPresent() throws Exception {
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:bar");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget);
    BuildResult fakeBuildResult =
        BuildResult.success(fakeBuildRule, BUILT_LOCALLY, CacheResult.miss());
    FakeBuildEngine fakeEngine =
        new FakeBuildEngine(ImmutableMap.of(fakeBuildTarget, fakeBuildResult));
    assertThat(fakeEngine.getBuildRuleResult(fakeBuildTarget), equalTo(fakeBuildResult));
  }

  @Test
  public void buildRuleIsBuilt() throws Exception {
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:bar");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget);
    BuildResult fakeBuildResult =
        BuildResult.success(fakeBuildRule, BUILT_LOCALLY, CacheResult.miss());
    FakeBuildEngine fakeEngine =
        new FakeBuildEngine(ImmutableMap.of(fakeBuildTarget, fakeBuildResult));
    assertThat(fakeEngine.isRuleBuilt(fakeBuildTarget), is(true));
  }

  @Test
  public void unbuiltRuleIsNotBuilt() throws Exception {
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//foo:bar");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(fakeBuildTarget);
    BuildResult fakeBuildResult =
        BuildResult.success(fakeBuildRule, BUILT_LOCALLY, CacheResult.miss());
    FakeBuildEngine fakeEngine =
        new FakeBuildEngine(ImmutableMap.of(fakeBuildTarget, fakeBuildResult));
    BuildTarget anotherFakeBuildTarget = BuildTargetFactory.newInstance("//foo:baz");
    assertThat(fakeEngine.isRuleBuilt(anotherFakeBuildTarget), is(false));
  }
}
