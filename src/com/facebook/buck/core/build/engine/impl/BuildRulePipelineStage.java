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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Creates and runs the steps for a single build rule within a pipeline, cascading any failures to
 * rules later in the pipeline.
 */
class BuildRulePipelineStage<T extends RulePipelineState>
    implements RunnableWithFuture<Optional<BuildResult>> {

  private final SupportsPipelining<T> rule;
  private final SettableFuture<Optional<BuildResult>> future;

  @Nullable private BuildRulePipelineStage<T> nextStage;
  @Nullable private Throwable error = null;
  @Nullable private Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepRunnerFactory;
  @Nullable private BuildRulePipeline<T> pipeline;

  BuildRulePipelineStage(SupportsPipelining<T> rule) {
    this.rule = rule;
    this.future = SettableFuture.create();
    Futures.addCallback(
        future,
        new FutureCallback<Optional<BuildResult>>() {
          @Override
          public void onSuccess(Optional<BuildResult> result) {}

          @Override
          public void onFailure(Throwable t) {
            error = t;
          }
        },
        MoreExecutors.directExecutor());
  }

  public void setRuleStepRunnerFactory(
      Function<T, RunnableWithFuture<Optional<BuildResult>>> ruleStepsFactory) {
    Preconditions.checkState(this.ruleStepRunnerFactory == null);
    this.ruleStepRunnerFactory = ruleStepsFactory;
  }

  public void setPipeline(BuildRulePipeline<T> pipeline) {
    Preconditions.checkState(this.pipeline == null);
    this.pipeline = pipeline;
  }

  public void setNextStage(BuildRulePipelineStage<T> nextStage) {
    Preconditions.checkState(this.nextStage == null);
    this.nextStage = nextStage;
  }

  @Nullable
  public BuildRulePipelineStage<T> getNextStage() {
    return nextStage;
  }

  public boolean pipelineBuilt() {
    return pipeline != null;
  }

  public void cancelAndWait() {
    // For now there's no cancel (cuz it's not hooked up at all), but we can at least wait
    try {
      getFuture().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) { // NOPMD
      // Ignore; the future is hooked up elsewhere and that location will handle the exceptions
    }
  }

  @Nullable
  public Throwable getError() {
    return error;
  }

  @Override
  public SettableFuture<Optional<BuildResult>> getFuture() {
    return future;
  }

  @Override
  public void run() {
    Preconditions.checkNotNull(pipeline);
    Preconditions.checkNotNull(ruleStepRunnerFactory);

    RunnableWithFuture<Optional<BuildResult>> runner =
        ruleStepRunnerFactory.apply(pipeline.getState());
    future.setFuture(runner.getFuture());
    runner.run();
  }

  public void abort(Throwable error) {
    future.setException(error);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("rule", rule).toString();
  }
}
