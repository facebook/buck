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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.engine.BuildEngine;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Map;

/** Fake implementation of {@link BuildEngine} for use in tests. */
public class FakeBuildEngine implements BuildEngine {

  private final ImmutableMap<BuildTarget, BuildResult> buildResults;

  public FakeBuildEngine(Map<BuildTarget, BuildResult> buildResults) {
    this.buildResults = ImmutableMap.copyOf(buildResults);
  }

  @Override
  public BuildEngineResult build(
      BuildEngineBuildContext buildContext, ExecutionContext executionContext, BuildRule rule) {
    SettableFuture<BuildResult> future = SettableFuture.create();
    future.set(buildResults.get(rule.getBuildTarget()));
    return BuildEngineResult.builder().setResult(future).build();
  }

  @Override
  public BuildResult getBuildRuleResult(BuildTarget buildTarget) {
    return buildResults.get(buildTarget);
  }

  @Override
  public boolean isRuleBuilt(BuildTarget buildTarget) {
    return buildResults.containsKey(buildTarget);
  }

  @Override
  public void terminateBuildWithFailure(Throwable failure) {
    // No-op
  }

  @Override
  public int getNumRulesToBuild(Iterable<BuildRule> rule) {
    return 0;
  }
}
