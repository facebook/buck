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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.util.concurrent.JobLimiter;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;

/**
 * This build strategy sends jobs to both a delegate build strategy and to the build engine to be
 * run in the default way.
 *
 * <p>It has a configurable limit for the number of active jobs to allow locally, and for the number
 * to allow to the delegate. It prefers to send them locally if the limit there hasn't been reached.
 * If both are at the limit, the jobs will be queued until space becomes available.
 */
public class HybridLocalStrategy implements BuildRuleStrategy {
  private final BuildRuleStrategy delegate;

  private final ConcurrentLinkedQueue<Job> pendingQueue;

  private final JobLimiter localLimiter;
  private final JobLimiter delegateLimiter;

  public HybridLocalStrategy(int numLocalJobs, int numDelegateJobs, BuildRuleStrategy delegate) {
    this.delegate = delegate;
    this.localLimiter = new JobLimiter(numLocalJobs);
    this.delegateLimiter = new JobLimiter(numDelegateJobs);
    this.pendingQueue = new ConcurrentLinkedQueue<>();
  }

  private static class Job {
    final BuildStrategyContext strategyContext;
    final BuildRule rule;
    final SettableFuture<Optional<BuildResult>> future;

    @Nullable volatile StrategyBuildResult delegateResult;

    Job(BuildStrategyContext strategyContext, BuildRule rule) {
      this.strategyContext = strategyContext;
      this.rule = rule;
      this.future = SettableFuture.create();
    }

    public void cancel() {
      if (delegateResult != null) {
        Objects.requireNonNull(delegateResult).cancel();
      }
    }
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    Job job = new Job(strategyContext, rule);
    pendingQueue.add(job);
    localLimiter.schedule(strategyContext.getExecutorService(), this::scheduleLocal);
    delegateLimiter.schedule(strategyContext.getExecutorService(), this::scheduleDelegated);
    return new StrategyBuildResult() {
      @Override
      public void cancel() {
        job.cancel();
      }

      @Override
      public ListenableFuture<Optional<BuildResult>> getBuildResult() {
        return job.future;
      }
    };
  }

  private ListenableFuture<?> scheduleLocal() {
    Job job = pendingQueue.poll();
    if (job == null) {
      return Futures.immediateFuture(null);
    }

    ListenableFuture<Optional<BuildResult>> localFuture =
        Futures.submitAsync(
            job.strategyContext::runWithDefaultBehavior, job.strategyContext.getExecutorService());
    job.future.setFuture(localFuture);
    return localFuture;
  }

  private ListenableFuture<?> scheduleDelegated() {
    Job job = pendingQueue.poll();
    if (job == null) {
      return Futures.immediateFuture(null);
    }

    StrategyBuildResult delegateResult = delegate.build(job.rule, job.strategyContext);
    job.delegateResult = delegateResult;
    ListenableFuture<Optional<BuildResult>> delegateFuture = delegateResult.getBuildResult();
    job.future.setFuture(delegateFuture);
    return delegateFuture;
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    return delegate.canBuild(instance);
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
