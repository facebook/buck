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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.Scope;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;

class SimpleBuildStrategyContext implements BuildStrategyContext {
  private final BuildRule rule;
  private final ListeningExecutorService service;

  public SimpleBuildStrategyContext(BuildRule rule, ListeningExecutorService service) {
    this.rule = rule;
    this.service = service;
  }

  @Override
  public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {
    return Futures.immediateFuture(
        Optional.of(createBuildResult(BuildRuleSuccessType.FETCHED_FROM_CACHE, Optional.empty())));
  }

  @Override
  public ListeningExecutorService getExecutorService() {
    return service;
  }

  @Override
  public BuildResult createBuildResult(
      BuildRuleSuccessType successType, Optional<String> strategyResult) {
    return BuildResult.builder()
        .setCacheResult(CacheResult.miss())
        .setRule(rule)
        .setStatus(BuildRuleStatus.SUCCESS)
        .setSuccessOptional(successType)
        .setStrategyResult(strategyResult)
        .build();
  }

  @Override
  public BuildResult createCancelledResult(Throwable throwable) {
    return BuildResult.builder()
        .setCacheResult(CacheResult.miss())
        .setRule(rule)
        .setStatus(BuildRuleStatus.CANCELED)
        .setFailureOptional(throwable)
        .build();
  }

  @Override
  public ExecutionContext getExecutionContext() {
    return TestExecutionContext.newInstance();
  }

  @Override
  public Scope buildRuleScope() {
    return () -> {};
  }

  @Override
  public BuildContext getBuildRuleBuildContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BuildableContext getBuildableContext() {
    throw new UnsupportedOperationException();
  }
}
