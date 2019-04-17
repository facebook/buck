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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.ListeningMultiSemaphore;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class HybridLocalStrategyTest {

  @Test
  public void testCanBuild() throws Exception {
    BuildRule good = new FakeBuildRule("//:target");
    BuildRule bad = new FakeBuildRule("//:target");
    BuildRuleStrategy delegate =
        new SimpleBuildRuleStrategy() {
          @Override
          public boolean canBuild(BuildRule instance) {
            return instance == good;
          }
        };

    try (HybridLocalStrategy strategy = new HybridLocalStrategy(1, 1, delegate)) {
      assertTrue(strategy.canBuild(good));
      assertFalse(strategy.canBuild(bad));
    }
  }

  @Test
  public void testLocalJobsLimited() throws Exception {
    int maxJobs = 1;
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 4));

    try {
      BuildRuleStrategy delegate = new SimpleBuildRuleStrategy();
      JobLimitingStrategyContextFactory contextFactory =
          new JobLimitingStrategyContextFactory(maxJobs, service);

      try (HybridLocalStrategy strategy = new HybridLocalStrategy(1, 0, delegate)) {
        List<ListenableFuture<Optional<BuildResult>>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
          FakeBuildRule rule = new FakeBuildRule("//:target-" + i);
          results.add(
              Futures.submitAsync(
                  () -> strategy.build(rule, contextFactory.createContext(rule)).getBuildResult(),
                  service));
        }

        contextFactory.waiting.release(3);
        assertTrue(contextFactory.finished.tryAcquire(3, 1, TimeUnit.SECONDS));
        assertFalse(contextFactory.finished.tryAcquire(20, TimeUnit.MILLISECONDS));

        contextFactory.waiting.release(7);
        assertTrue(contextFactory.finished.tryAcquire(7, 1, TimeUnit.SECONDS));

        Futures.allAsList(results).get(1, TimeUnit.SECONDS);
        for (ListenableFuture<Optional<BuildResult>> r : results) {
          assertTrue(r.isDone());
          assertTrue(r.get().get().isSuccess());
        }
      }
    } finally {
      service.shutdownNow();
    }
  }

  @Test
  public void testDelegateJobsLimited() throws Exception {
    int maxJobs = 1;
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 4));

    try {
      JobLimitingBuildRuleStrategy delegate = new JobLimitingBuildRuleStrategy(maxJobs, service);

      try (HybridLocalStrategy strategy = new HybridLocalStrategy(0, 1, delegate)) {
        List<ListenableFuture<Optional<BuildResult>>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
          FakeBuildRule rule = new FakeBuildRule("//:target-" + i);
          results.add(
              Futures.submitAsync(
                  () ->
                      strategy
                          .build(rule, new SimpleBuildStrategyContext(rule, service))
                          .getBuildResult(),
                  service));
        }

        delegate.waiting.release(3);
        assertTrue(delegate.finished.tryAcquire(3, 1, TimeUnit.SECONDS));
        assertFalse(delegate.finished.tryAcquire(20, TimeUnit.MILLISECONDS));

        delegate.waiting.release(7);
        assertTrue(delegate.finished.tryAcquire(7, 1, TimeUnit.SECONDS));

        Futures.allAsList(results).get(1, TimeUnit.SECONDS);
        for (ListenableFuture<Optional<BuildResult>> r : results) {
          assertTrue(r.isDone());
          assertTrue(r.get().get().isSuccess());
        }
      }
    } finally {
      service.shutdownNow();
    }
  }

  @Test
  public void testCanStealJobs() throws Exception {
    Semaphore cancelled = new Semaphore(0);
    Semaphore delegateStarted = new Semaphore(0);

    int maxJobs = 1;
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 4));

    try {
      BuildRuleStrategy delegate =
          new SimpleBuildRuleStrategy() {
            @Override
            public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
              delegateStarted.release();
              return new StrategyBuildResult() {
                SettableFuture<Optional<BuildResult>> future = SettableFuture.create();

                @Override
                public void cancel(Throwable cause) {}

                @Override
                public boolean cancelIfNotComplete(Throwable reason) {
                  cancelled.release();
                  future.set(Optional.of(strategyContext.createCancelledResult(reason)));
                  return true;
                }

                @Override
                public ListenableFuture<Optional<BuildResult>> getBuildResult() {
                  return future;
                }
              };
            }
          };
      JobLimitingStrategyContextFactory contextFactory =
          new JobLimitingStrategyContextFactory(maxJobs, service);

      try (HybridLocalStrategy strategy = new HybridLocalStrategy(1, 10, delegate)) {
        List<ListenableFuture<Optional<BuildResult>>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
          FakeBuildRule rule = new FakeBuildRule("//:target-" + i);
          results.add(
              Futures.submitAsync(
                  () -> strategy.build(rule, contextFactory.createContext(rule)).getBuildResult(),
                  service));
        }

        assertTrue(delegateStarted.tryAcquire(9, 1, TimeUnit.SECONDS));
        contextFactory.waiting.release(3);
        assertTrue(contextFactory.finished.tryAcquire(3, 1, TimeUnit.SECONDS));
        assertTrue(cancelled.tryAcquire(3, 1, TimeUnit.SECONDS));

        assertFalse(contextFactory.finished.tryAcquire(20, TimeUnit.MILLISECONDS));

        contextFactory.waiting.release(7);
        assertTrue(contextFactory.finished.tryAcquire(7, 1, TimeUnit.SECONDS));
        assertTrue(cancelled.tryAcquire(6, 1, TimeUnit.SECONDS));

        Futures.allAsList(results).get(1, TimeUnit.SECONDS);
        for (ListenableFuture<Optional<BuildResult>> r : results) {
          assertTrue(r.isDone());
          assertTrue(r.get().get().isSuccess());
        }
      }
    } finally {
      service.shutdownNow();
    }
  }

  @Test
  public void testJobsGetStolenConsistently() throws Exception {
    // When the HybridLocal strategy is working correctly, this shouldn't have any flakiness. If
    // hybrid local has a race, it's difficult to write a test that identifies it with no flakiness
    // (though this test was failing in 100/100 tests on a particular race).
    ResourceAmounts resourceAmounts = ResourceAmounts.of(8, 1, 0, 0);
    ListeningExecutorService service =
        new WeightedListeningExecutorService(
            new ListeningMultiSemaphore(resourceAmounts, ResourceAllocationFairness.FAST),
            ResourceAmounts.zero(),
            MoreExecutors.listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 8)));

    try {
      AtomicInteger delegateScheduled = new AtomicInteger();
      BuildRuleStrategy delegate =
          new SimpleBuildRuleStrategy() {
            @Override
            public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
              delegateScheduled.incrementAndGet();
              return new StrategyBuildResult() {
                SettableFuture<Optional<BuildResult>> future = SettableFuture.create();

                @Override
                public void cancel(Throwable cause) {}

                @Override
                public boolean cancelIfNotComplete(Throwable reason) {
                  System.err.println("Stealing");
                  return true;
                }

                @Override
                public ListenableFuture<Optional<BuildResult>> getBuildResult() {
                  return future;
                }
              };
            }
          };

      try (HybridLocalStrategy strategy = new HybridLocalStrategy(1, 100, delegate)) {
        List<ListenableFuture<?>> futures = new ArrayList<>();
        // We don't want any local jobs to finish before we've scheduled everything (if they did,
        // it's possible that some hybrid implementation could just chew through them without really
        // sending anything to the delegate).
        SettableFuture<Optional<BuildResult>> localResult = SettableFuture.create();
        for (int i = 0; i < 1000; i++) {
          FakeBuildRule rule = new FakeBuildRule("//:target-" + i);
          if (delegateScheduled.get() >= 80 && i >= 200) {
            // We also don't want to wait for everything to be sent to the strategy.
            localResult.set(Optional.empty());
          }
          SimpleBuildStrategyContext strategyContext =
              new SimpleBuildStrategyContext(rule, service) {
                @Override
                public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {
                  return localResult;
                }

                @Override
                public ListeningExecutorService getExecutorService() {
                  return ((WeightedListeningExecutorService) service)
                      .withDefaultAmounts(ResourceAmounts.of(0, 1, 0, 0));
                }
              };
          futures.add(
              Futures.submitAsync(
                  () -> strategy.build(rule, strategyContext).getBuildResult(), service));
        }

        assertTrue(localResult.isDone());

        try {
          Futures.allAsList(futures).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
          List<Pair<Integer, ListenableFuture<?>>> unfinished = new ArrayList<>();
          for (int i = 0; i < futures.size(); i++) {
            if (!futures.get(i).isDone()) {
              unfinished.add(new Pair<>(i, futures.get(i)));
            }
          }
          assertEquals(ImmutableList.of(), unfinished);
        }
      }
    } finally {
      service.shutdownNow();
    }
  }

  @Test
  public void testWhenCancelIfNotStartedReturnsFalseJobsArentStolen() throws Exception {
    Semaphore cancelled = new Semaphore(0);
    Semaphore delegateStarted = new Semaphore(0);

    int maxJobs = 1;
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 4));

    try {
      BuildRuleStrategy delegate =
          new SimpleBuildRuleStrategy() {
            @Override
            public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
              return new StrategyBuildResult() {
                SettableFuture<Optional<BuildResult>> future = SettableFuture.create();

                @Override
                public void cancel(Throwable cause) {}

                @Override
                public boolean cancelIfNotComplete(Throwable reason) {
                  cancelled.release();
                  future.set(
                      Optional.of(
                          strategyContext.createBuildResult(
                              BuildRuleSuccessType.BUILT_LOCALLY, Optional.empty())));
                  return false;
                }

                @Override
                public ListenableFuture<Optional<BuildResult>> getBuildResult() {
                  delegateStarted.release();
                  return future;
                }
              };
            }
          };
      JobLimitingStrategyContextFactory contextFactory =
          new JobLimitingStrategyContextFactory(maxJobs, service);

      try (HybridLocalStrategy strategy = new HybridLocalStrategy(1, 10, delegate)) {
        List<ListenableFuture<Optional<BuildResult>>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
          FakeBuildRule rule = new FakeBuildRule("//:target-" + i);
          results.add(
              Futures.submitAsync(
                  () -> strategy.build(rule, contextFactory.createContext(rule)).getBuildResult(),
                  service));
        }

        assertTrue(delegateStarted.tryAcquire(9, 1, TimeUnit.SECONDS));
        contextFactory.waiting.release(1);
        assertTrue(contextFactory.finished.tryAcquire(1, 1, TimeUnit.SECONDS));
        assertFalse(contextFactory.finished.tryAcquire(20, TimeUnit.MILLISECONDS));

        // After releasing 1, the HybridLocal strategy should've gone through and tried to steal
        // everything from the delegate which should've triggered them all to complete.
        Futures.allAsList(results).get(1, TimeUnit.SECONDS);
        for (ListenableFuture<Optional<BuildResult>> r : results) {
          assertTrue(r.isDone());
          assertTrue(r.get().get().isSuccess());
        }
      }
    } finally {
      service.shutdownNow();
    }
  }

  static class SimpleBuildRuleStrategy implements BuildRuleStrategy {
    @Override
    public void close() {}

    @Override
    public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
      ListenableFuture<Optional<BuildResult>> buildResult =
          Futures.immediateFuture(
              Optional.of(
                  strategyContext.createBuildResult(
                      BuildRuleSuccessType.BUILT_LOCALLY, Optional.empty())));
      return StrategyBuildResult.nonCancellable(buildResult);
    }

    @Override
    public boolean canBuild(BuildRule instance) {
      return true;
    }
  }

  static class JobLimitingBuildRuleStrategy extends SimpleBuildRuleStrategy {
    // The created strategy will try to acquire this semaphore before proceeding in build().
    private final Semaphore waiting;
    // The created strategy will release a permit on this semaphore when it finishes build().
    private final Semaphore finished;
    private final int maxJobs;
    private final ListeningExecutorService service;
    private final AtomicInteger numCurrentJobs = new AtomicInteger();

    public JobLimitingBuildRuleStrategy(int maxJobs, ListeningExecutorService service) {
      this.waiting = new Semaphore(0);
      this.finished = new Semaphore(0);
      this.maxJobs = maxJobs;
      this.service = service;
    }

    @Override
    public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
      assertTrue(numCurrentJobs.incrementAndGet() <= maxJobs);
      try {
        assertTrue(waiting.tryAcquire(1, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      ListenableFuture<Optional<BuildResult>> result =
          service.submit(
              () -> {
                assertTrue(numCurrentJobs.get() <= maxJobs);
                numCurrentJobs.decrementAndGet();
                finished.release();
                return Optional.of(
                    strategyContext.createBuildResult(
                        BuildRuleSuccessType.BUILT_LOCALLY, Optional.empty()));
              });

      return StrategyBuildResult.nonCancellable(result);
    }
  }

  private static class JobLimitingStrategyContextFactory {
    // The created default behavior will try to acquire this semaphore before proceeding.
    private final Semaphore waiting;
    // The created default behavior will release a permit on this semaphore when it finishes.
    private final Semaphore finished;
    private final int maxJobs;
    private final ListeningExecutorService service;
    private final AtomicInteger busy = new AtomicInteger();

    public JobLimitingStrategyContextFactory(int maxJobs, ListeningExecutorService service) {
      this.waiting = new Semaphore(0);
      this.finished = new Semaphore(0);
      this.maxJobs = maxJobs;
      this.service = service;
    }

    BuildStrategyContext createContext(BuildRule rule) {
      return new SimpleBuildStrategyContext(rule, service) {
        @Override
        public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {
          assertTrue(busy.incrementAndGet() <= maxJobs);
          try {
            assertTrue(waiting.tryAcquire(1, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          assertTrue(busy.get() <= maxJobs);

          return service.submit(
              () -> {
                assertTrue(busy.get() <= maxJobs);
                busy.decrementAndGet();
                finished.release();
                return Optional.of(
                    createBuildResult(BuildRuleSuccessType.FETCHED_FROM_CACHE, Optional.empty()));
              });
        }
      };
    }
  }

  // This makes debugging much simpler. The assertions happen on other threads and make it into
  // Future results, but aren't printed as errors and manifest as failures to acquire semaphores.
  private static void assertTrue(boolean condition) {
    if (!condition) {
      Thread.dumpStack();
    }
    Assert.assertTrue(condition);
  }

  private static class SimpleBuildStrategyContext implements BuildStrategyContext {
    private final BuildRule rule;
    private final ListeningExecutorService service;

    public SimpleBuildStrategyContext(BuildRule rule, ListeningExecutorService service) {
      this.rule = rule;
      this.service = service;
    }

    @Override
    public ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior() {
      return Futures.immediateFuture(
          Optional.of(
              createBuildResult(BuildRuleSuccessType.FETCHED_FROM_CACHE, Optional.empty())));
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
}
