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
import com.facebook.buck.core.build.engine.DelegatingBuildStrategyContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.JobLimiter;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
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
  private static final Logger LOG = Logger.get(HybridLocalStrategy.class);

  private final BuildRuleStrategy delegate;

  private final ConcurrentLinkedQueue<Job> pendingQueue;

  private final JobLimiter localLimiter;
  private final JobLimiter delegateLimiter;

  private final DelegateJobTracker tracker = new DelegateJobTracker();

  private static class DelegateJobTracker {
    ConcurrentLinkedDeque<Job> delegateJobs = new ConcurrentLinkedDeque<>();

    void register(Job job) {
      delegateJobs.addLast(job);
    }

    @Nullable
    Job stealFromDelegate() {
      while (true) {
        Job job = delegateJobs.pollLast();
        if (job == null) {
          return null;
        }
        try {
          if (job.cancelDelegate(new CancellationException("Job is being stolen."))) {
            return job;
          }
        } catch (Exception e) {
          LOG.info(e, "Got an exception while trying to cancel the remotely scheduled job.");
          job.future.setException(
              new BuckUncheckedExecutionException(
                  e, "When trying to steal remotely scheduled job."));
          return null;
        }
      }
    }
  }

  public HybridLocalStrategy(int numLocalJobs, int numDelegateJobs, BuildRuleStrategy delegate) {
    this.delegate = delegate;
    this.localLimiter = new JobLimiter(numLocalJobs);
    this.delegateLimiter = new JobLimiter(numDelegateJobs);
    this.pendingQueue = new ConcurrentLinkedQueue<>();
  }

  // The stage is used to track the current stage of a Job. It's just used to defensively catch some
  // invalid states. It can only increase, except for a failed request for cancellation.
  private enum JobStage {
    PENDING,
    DELEGATE_SCHEDULED,
    REQUEST_DELEGATE_CANCELLED,
    DELEGATE_CANCELLED,
    // There's no LOCAL_SCHEDULED because once the local build has started its out of our handles
    // and we set the future immediately.
    FINISHED
  }

  private class Job {
    final BuildStrategyContext strategyContext;
    final BuildRule rule;
    final SettableFuture<Optional<BuildResult>> future;

    volatile JobStage stage;
    @Nullable volatile StrategyBuildResult delegateResult;

    Job(BuildStrategyContext strategyContext, BuildRule rule) {
      this.strategyContext = strategyContext;
      this.rule = rule;
      this.future = SettableFuture.create();
      this.stage = JobStage.PENDING;
    }

    private void advanceStage(JobStage newStage) {
      Preconditions.checkState(stage.ordinal() < newStage.ordinal());
      stage = newStage;
    }

    ListenableFuture<?> scheduleLocally() {
      synchronized (this) {
        if (stage == JobStage.FINISHED) {
          return future;
        }
        Preconditions.checkState(stage != JobStage.DELEGATE_SCHEDULED);
        advanceStage(JobStage.FINISHED);

        ListenableFuture<Optional<BuildResult>> localFuture =
            Futures.submitAsync(
                strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService());
        future.setFuture(localFuture);
        return localFuture;
      }
    }

    ListenableFuture<?> scheduleWithDelegate() {
      synchronized (this) {
        if (stage == JobStage.FINISHED) {
          return future;
        }
        advanceStage(JobStage.DELEGATE_SCHEDULED);
        delegateResult =
            delegate.build(rule, new DelegatingContextWithNoOpRuleScope(strategyContext));
        tracker.register(this);
        ListenableFuture<Optional<BuildResult>> buildResult =
            Objects.requireNonNull(delegateResult).getBuildResult();
        Futures.addCallback(buildResult, MoreFutures.finallyCallback(this::handleDelegateResult));
        return buildResult;
      }
    }

    private void handleDelegateResult(ListenableFuture<Optional<BuildResult>> delegateResult) {
      // It's possible for this to be called on the same thread that is attempting a cancellation.
      // In that case, the stage will be REQUEST_DELEGATE_CANCELLED. Because we don't know if we are
      // entering this due to a successful cancellation or due to an unsuccessful cancellation, we
      // must ignore the result here and let cancelDelegate() deal with it.
      synchronized (this) {
        if (stage == JobStage.DELEGATE_SCHEDULED) {
          advanceStage(JobStage.FINISHED);
          future.setFuture(delegateResult);
        }
      }
    }

    public void cancel(Throwable reason) {
      synchronized (this) {
        if (stage == JobStage.FINISHED) {
          return;
        }
        Optional<BuildResult> cancelledResult =
            Optional.of(strategyContext.createCancelledResult(reason));
        // TODO(cjhopman): We should probably have a more forceful cancellation that succeeds as
        // long as the delegate can ensure no future side effects will happen.
        if (stage != JobStage.DELEGATE_SCHEDULED || cancelDelegate(reason)) {
          future.set(cancelledResult);
        } else {
          // We're unable to cancel the delegate, so delay the cancelled result until the delegate
          // finishes.
          future.setFuture(
              Futures.transform(
                  Objects.requireNonNull(delegateResult).getBuildResult(),
                  ignored -> cancelledResult));
        }
        advanceStage(JobStage.FINISHED);
      }
    }

    public boolean cancelDelegate(Throwable reason) {
      synchronized (this) {
        if (stage != JobStage.DELEGATE_SCHEDULED) {
          return true;
        }
        advanceStage(JobStage.REQUEST_DELEGATE_CANCELLED);
        StrategyBuildResult delegateBuildResult = Objects.requireNonNull(delegateResult);
        if (delegateBuildResult.cancelIfNotStarted(reason)) {
          advanceStage(JobStage.DELEGATE_CANCELLED);
          return true;
        }
        // Set the stage back to DELEGATE_SCHEDULED so that we accept its result.
        stage = JobStage.DELEGATE_SCHEDULED;
        // It's possible that cancelIfNotStarted() (oddly) triggered successful completion of the
        // rule. In that case, we would've ignored the result in handleDelegateResult() and need to
        // set it here. (this currently only happens in tests)
        if (delegateBuildResult.getBuildResult().isDone()) {
          handleDelegateResult(delegateBuildResult.getBuildResult());
        }
        return false;
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
      public boolean cancelIfNotStarted(Throwable reason) {
        // TODO(cjhopman): Should we implement this?
        return false;
      }

      @Override
      public void cancel(Throwable cause) {
        job.cancel(cause);
      }

      @Override
      public ListenableFuture<Optional<BuildResult>> getBuildResult() {
        return job.future;
      }
    };
  }

  private ListenableFuture<?> scheduleLocal() {
    Job job = null;
    try {
      job = pendingQueue.poll();
      if (job == null) {
        job = tracker.stealFromDelegate();
        if (job == null) {
          return Futures.immediateFuture(null);
        }
      }
      return job.scheduleLocally();
    } catch (Exception e) {
      // This shouldn't happen and indicates a programming error that we don't know how to recover
      // from.
      LOG.error(e, "Exception thrown in hybrid_local scheduling.");
      if (job != null) {
        job.future.setException(e);
      }
      return Futures.immediateFuture(null);
    }
  }

  private ListenableFuture<?> scheduleDelegated() {
    Job job = pendingQueue.poll();
    if (job == null) {
      return Futures.immediateFuture(null);
    }

    try {
      return job.scheduleWithDelegate();
    } catch (Exception e) {
      // This shouldn't happen and indicates a programming error that we don't know how to recover
      // from.
      LOG.error(e, "Exception thrown in hybrid_local scheduling.");
      job.future.setException(e);
      return Futures.immediateFuture(null);
    }
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    return delegate.canBuild(instance);
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  private static class DelegatingContextWithNoOpRuleScope extends DelegatingBuildStrategyContext {
    public DelegatingContextWithNoOpRuleScope(BuildStrategyContext delegate) {
      super(delegate);
    }

    @Override
    public Scope buildRuleScope() {
      // TODO(cjhopman): If we want the delegate strategy to be able to put things in
      // a buildRuleScope, we need to shut them down when we cancel it.
      return () -> {};
    }
  }
}
