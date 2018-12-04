/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.build.engine;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Scope;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;

/**
 * A simple delegate {@link BuildStrategyContext} to make it easier to change parts of the behavior.
 */
public class DelegatingBuildStrategyContext implements BuildStrategyContext {
  private final BuildStrategyContext delegateContext;

  public DelegatingBuildStrategyContext(BuildStrategyContext delegateContext) {
    this.delegateContext = delegateContext;
  }

  @Override
  public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {
    return delegateContext.runWithDefaultBehavior();
  }

  @Override
  public ListeningExecutorService getExecutorService() {
    return delegateContext.getExecutorService();
  }

  @Override
  public BuildResult createBuildResult(BuildRuleSuccessType successType) {
    return delegateContext.createBuildResult(successType);
  }

  @Override
  public BuildResult createCancelledResult(Throwable throwable) {
    return delegateContext.createCancelledResult(throwable);
  }

  @Override
  public ExecutionContext getExecutionContext() {
    return delegateContext.getExecutionContext();
  }

  @Override
  public Scope buildRuleScope() {
    return delegateContext.buildRuleScope();
  }

  @Override
  public BuildContext getBuildRuleBuildContext() {
    return delegateContext.getBuildRuleBuildContext();
  }

  @Override
  public BuildableContext getBuildableContext() {
    return delegateContext.getBuildableContext();
  }
}
