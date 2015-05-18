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

import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.concurrent.ExecutionException;
import java.util.Map;

/**
 * Fake implementation of {@link BuildEngine} for use in tests.
 */
public class FakeBuildEngine implements BuildEngine {

  private final ImmutableMap<BuildTarget, BuildResult> buildResults;
  private final ImmutableMap<BuildTarget, RuleKey> ruleKeys;

  public FakeBuildEngine(
      Map<BuildTarget, BuildResult> buildResults,
      Map<BuildTarget, RuleKey> ruleKeys) {
    this.buildResults = ImmutableMap.copyOf(buildResults);
    this.ruleKeys = ImmutableMap.copyOf(ruleKeys);
  }

  @Override
  public ListenableFuture<BuildResult> build(BuildContext context, BuildRule rule) {
    SettableFuture<BuildResult> future = SettableFuture.create();
    future.set(buildResults.get(rule.getBuildTarget()));
    return future;
  }

  @Override
  public BuildResult getBuildRuleResult(BuildTarget buildTarget)
    throws ExecutionException, InterruptedException {
    return buildResults.get(buildTarget);
  }

  @Override
  public boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException {
    return buildResults.containsKey(buildTarget);
  }

  @Override
  public RuleKey getRuleKey(BuildTarget buildTarget) {
    return ruleKeys.get(buildTarget);
  }
}
