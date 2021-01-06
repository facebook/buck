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

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.engine.DelegatingBuildStrategyContext;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.WorkerRequirementsProvider;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
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

  // Queue for Jobs that cannot be run on the delegate
  private final ConcurrentLinkedQueue<Job> pendingLocalQueue;
  // Queue for Jobs that can be run on the delegate or locally
  private final ConcurrentLinkedQueue<Job> pendingDelegateOrLocalQueue;
  // Queue for Jobs that can only be run on delegate
  private final ConcurrentLinkedQueue<Job> pendingDelegateOnlyQueue;

  private final Semaphore localSemaphore;
  private final Semaphore localDelegateSemaphore;
  private final Semaphore delegateSemaphore;
  private BuckEventBus eventBus;

  private final DelegateJobTracker tracker = new DelegateJobTracker();

  private final ListeningExecutorService scheduler =
      MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

  private final Optional<WorkerRequirements.WorkerSize> maxWorkerSizeToStealFrom;

  private final WorkerRequirementsProvider workerRequirementsProvider;

  private final String auxiliaryBuildTag;

  // If this is non-null, we've hit some unexpected unrecoverable condition.
  @Nullable private volatile Throwable hardFailure;

  private static class DelegateJobTracker {
    ConcurrentLinkedDeque<Job> delegateJobs = new ConcurrentLinkedDeque<>();

    void register(Job job) {
      delegateJobs.addLast(job);
    }

    @Nullable
    ListenableFuture<?> stealFromDelegate(BuckEventBus eventBus) {
      while (true) {
        Job job = delegateJobs.pollLast();
        if (job == null) {
          return null;
        }
        try {
          ListenableFuture<?> listenableFuture =
              job.rescheduleLocally(new CancellationException("Job is being stolen."));
          if (listenableFuture != null) {
            eventBus.post(HybridLocalEvent.createStolen(job.rule.getBuildTarget()));
            return listenableFuture;
          }
        } catch (Exception e) {
          LOG.info(
              e,
              "Got an exception while trying to cancel the remotely scheduled job building: %s",
              job.rule.getFullyQualifiedName());
          job.future.setException(
              new BuckUncheckedExecutionException(
                  e, "When trying to steal remotely scheduled job."));
          return null;
        }
      }
    }
  }

  public HybridLocalStrategy(
      int numLocalJobs,
      int numLocalDelegateJobs,
      int numDelegateJobs,
      BuildRuleStrategy delegate,
      WorkerRequirementsProvider workerRequirementsProvider,
      Optional<WorkerRequirements.WorkerSize> maxWorkerSizeToStealFrom,
      String auxiliaryBuildTag,
      BuckEventBus eventBus) {
    this.delegate = delegate;
    this.workerRequirementsProvider = workerRequirementsProvider;
    this.maxWorkerSizeToStealFrom = maxWorkerSizeToStealFrom;
    this.auxiliaryBuildTag = auxiliaryBuildTag;
    this.localSemaphore = new Semaphore(numLocalJobs);
    this.localDelegateSemaphore = new Semaphore(numLocalDelegateJobs);
    this.delegateSemaphore = new Semaphore(numDelegateJobs);
    this.eventBus = eventBus;
    this.pendingLocalQueue = new ConcurrentLinkedQueue<>();
    this.pendingDelegateOrLocalQueue = new ConcurrentLinkedQueue<>();
    this.pendingDelegateOnlyQueue = new ConcurrentLinkedQueue<>();
  }

  boolean isStealingSupportedForJob(Job job) {
    // Ensure that we do not steal actions for which require a worker greater than the max
    // configured limit, as this could lead to OOMs on the local machine.
    if (!maxWorkerSizeToStealFrom.isPresent()) {
      return true;
    }

    WorkerRequirements.WorkerSize workerSizeRequirement =
        workerRequirementsProvider
            .resolveRequirements(job.rule.getBuildTarget(), auxiliaryBuildTag)
            .getWorkerSize();

    return workerSizeRequirement.getNumber() <= maxWorkerSizeToStealFrom.get().getNumber();
  }

  private class Job {
    final BuildStrategyContext strategyContext;
    final BuildRule rule;
    final SettableFuture<Optional<BuildResult>> future;

    // The delegateResult is null if we either (1) haven't schedule the delegate yet or (2) have (or
    // are in the process of) cancelling the delegate.
    @Nullable StrategyBuildResult delegateResult;
    volatile boolean cancelledOnDelegate;
    final boolean canBuildOnDelegate;

    Job(BuildStrategyContext strategyContext, BuildRule rule, boolean canBuildOnDelegate) {
      this.strategyContext = strategyContext;
      this.rule = rule;
      this.future = SettableFuture.create();
      this.cancelledOnDelegate = false;
      this.canBuildOnDelegate = canBuildOnDelegate;
    }

    // TODO(cjhopman): These schedule functions might not be resilient in the face of exceptions
    // thrown within them, we might end up with a future stuck in a state where it will never be
    // finished.
    ListenableFuture<?> scheduleLocally() {
      synchronized (this) {
        if (future.isDone()) {
          return Futures.immediateFuture(Unit.UNIT);
        }

        ListenableFuture<Optional<BuildResult>> localFuture =
            Futures.transform(
                Futures.submitAsync(
                    strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService()),
                result ->
                    Optional.of(
                        BuildResult.builder()
                            .from(result.get())
                            .setStrategyResult(
                                "hybrid local"
                                    + (canBuildOnDelegate ? " - delegate" : " - nondelegate")
                                    + (cancelledOnDelegate ? " - stolen" : ""))
                            .build()),
                MoreExecutors.directExecutor());
        future.setFuture(localFuture);
        return localFuture;
      }
    }

    ListenableFuture<?> scheduleWithDelegate() {
      synchronized (this) {
        if (future.isDone()) {
          return Futures.immediateFuture(null);
        }
        StrategyBuildResult capturedDelegateResult =
            delegate.build(rule, new DelegatingContextWithNoOpRuleScope(strategyContext));
        delegateResult = capturedDelegateResult;

        // Only register delegate job if there is a possibility for it to be stolen
        if (isStealingSupportedForJob(this)) {
          tracker.register(this);
        }

        ListenableFuture<Optional<BuildResult>> buildResult =
            capturedDelegateResult.getBuildResult();
        buildResult.addListener(this::handleDelegateResult, MoreExecutors.directExecutor());
        return buildResult;
      }
    }

    private void handleDelegateResult() {
      // If this.delegateResult is null, we either cancelled the delegate or are in the process of
      // doing so. Either way we ignore it.
      // In some edge cases, this may be called multiple times with non-null delegateResult. That's
      // fine since setFuture() will ignore the later ones.
      synchronized (this) {
        StrategyBuildResult capturedDelegateResult = this.delegateResult;
        if (capturedDelegateResult != null) {
          future.setFuture(capturedDelegateResult.getBuildResult());
        }
      }
    }

    public void cancel(Throwable reason) {
      synchronized (this) {
        if (future.isDone()) {
          return;
        }
        LOG.info("Canceling job building: %s", rule.getFullyQualifiedName());
        Optional<BuildResult> cancelledResult =
            Optional.of(strategyContext.createCancelledResult(reason));
        // TODO(cjhopman): We should probably have a more forceful cancellation that succeeds as
        // long as the delegate can ensure no future side effects will happen.

        if (this.delegateResult != null) {
          cancelDelegateLocked(reason);
        }

        if (this.delegateResult == null) {
          future.set(cancelledResult);
        } else {
          // We're unable to cancel the delegate, so delay the cancelled result until the delegate
          // finishes.
          future.setFuture(
              Futures.transform(
                  this.delegateResult.getBuildResult(),
                  ignored -> cancelledResult,
                  MoreExecutors.directExecutor()));
        }
      }
    }

    public ListenableFuture<?> rescheduleLocally(Throwable reason) {
      synchronized (this) {
        if (!cancelDelegateLocked(reason)) {
          return null;
        }
        return scheduleLocally();
      }
    }

    public boolean cancelDelegateLocked(Throwable reason) {
      Verify.verify(Thread.holdsLock(this));

      StrategyBuildResult capturedDelegateResult = Objects.requireNonNull(delegateResult);
      if (capturedDelegateResult.getBuildResult().isDone()) {
        return false;
      }

      // We do a sort of weird little dance here mostly to correctly handle these cases:
      // 1. delegate's cancelIfNotComplete() implementation directly completing its future in the
      // successful cancellation case.
      // 2. normal delegate completion that races with cancelIfNotComplete().
      // 3. delegate's cancelIfNotComplete() implementation directly completing its future in the
      // unsuccessful cancellation case.
      //
      // To handle (1), we clear this.delegateResult so that handleDelegateResult ignores it.
      // To handle (2), we then need to re-set the delegate result
      // To handle (3), we then explicitly call handleDelegateResult if the delegate has finished
      //
      // This would probably all be simpler if we either restricted the allowed behavior of
      // cancelIfNotComplete() or made the StrategyBuildResult richer such that we didn't need to
      // clear it here.
      this.delegateResult = null;
      if (capturedDelegateResult.cancelIfNotComplete(reason)) {
        cancelledOnDelegate = true;
        return true;
      }
      this.delegateResult = capturedDelegateResult;
      if (capturedDelegateResult.getBuildResult().isDone()) {
        handleDelegateResult();
      }
      return false;
    }
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    boolean canBuildOnDelegate = delegate.canBuild(rule);
    Job job = new Job(strategyContext, rule, canBuildOnDelegate);

    if (canBuildOnDelegate) {
      if (isStealingSupportedForJob(job)) {
        pendingDelegateOrLocalQueue.add(job);
      } else {
        pendingDelegateOnlyQueue.add(job);
      }

    } else {
      pendingLocalQueue.add(job);
    }

    scheduler.submit(this::schedule);
    return new StrategyBuildResult() {
      @Override
      public boolean cancelIfNotComplete(Throwable reason) {
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

  private void schedule() {
    if (hardFailure != null) {
      cancelAllPendingJobs();
      return;
    }

    try {
      // Try scheduling a local task from local or delegate queues.
      semaphoreScopedSchedule(
          localSemaphore,
          () -> {
            Job job = pendingLocalQueue.poll();
            if (job != null) {
              return job.scheduleLocally();
            } else {
              if (localDelegateSemaphore.tryAcquire()) {
                job = pendingDelegateOrLocalQueue.poll();
                ListenableFuture<?> future;
                if (job != null) {
                  future = job.scheduleLocally();
                } else {
                  future = tracker.stealFromDelegate(eventBus);
                }
                if (future != null) {
                  SettableFuture<Object> semaphoreFuture = SettableFuture.create();
                  Futures.addCallback(
                      future,
                      new FutureCallback<Object>() {

                        @Override
                        public void onSuccess(@Nullable Object result) {
                          localDelegateSemaphore.release();
                          semaphoreFuture.set(result);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                          localDelegateSemaphore.release();
                          semaphoreFuture.setException(t);
                        }
                      },
                      MoreExecutors.directExecutor());

                  return semaphoreFuture;
                } else {
                  localDelegateSemaphore.release();
                  return null;
                }
              }
              return null;
            }
          });

      // Try scheduling a delegate task.
      semaphoreScopedSchedule(
          delegateSemaphore,
          () -> {
            // Actions that can only run by delegate should be scheduled with priority
            Job job = pendingDelegateOnlyQueue.poll();

            if (job == null) {
              job = pendingDelegateOrLocalQueue.poll();
            }
            return job == null ? null : job.scheduleWithDelegate();
          });
    } catch (Throwable t) {
      // We have no way of handling failures that occur during scheduling.
      LOG.error(t, "Unexpected error during strategy scheduling.");
      hardFailure = t;
      cancelAllPendingJobs();
    }
  }

  private void cancelAllJobsInQueue(ConcurrentLinkedQueue<Job> jobQueue) {
    while (!jobQueue.isEmpty()) {
      Objects.requireNonNull(jobQueue.poll()).cancel(Objects.requireNonNull(hardFailure));
    }
  }

  private void cancelAllPendingJobs() {
    // Only the scheduling thread pulls from the queue, so polling from queues is safe.
    cancelAllJobsInQueue(pendingDelegateOrLocalQueue);
    cancelAllJobsInQueue(pendingDelegateOnlyQueue);
    cancelAllJobsInQueue(pendingLocalQueue);
  }

  // Attempts to acquire a permit from the semaphore and then tries to schedule the task provided.
  // If null is returned from the task supplier, the permit will be released immediately, otherwise
  // it'll be released when the task finishes.
  private void semaphoreScopedSchedule(
      Semaphore semaphore, Supplier<ListenableFuture<?>> taskSupplier) {
    while (true) {
      if (!semaphore.tryAcquire()) {
        return;
      }

      ListenableFuture<?> future = taskSupplier.get();

      if (future == null) {
        semaphore.release();
        return;
      }

      future.addListener(
          () -> {
            semaphore.release();
            scheduler.execute(this::schedule);
          },
          MoreExecutors.directExecutor());
    }
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    return true;
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
