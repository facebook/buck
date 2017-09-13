/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.WorkThreadTrackingFuture;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveTask;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Implementation of BuildRuleResolver that supports build rules being created in parallel.
 *
 * <p>In order to prevent race-conditions and duplicate work, this implementation imposes additional
 * semantics for concurrent access:
 *
 * <ul>
 *   <li>Rules are stored in futures.
 *   <li>Accessing incomplete rules from the current thread behaves as if the rule does not exist.
 *       Accessing incomplete rules from other threads waits for the rule future to complete.
 * </ul>
 */
public class MultiThreadedBuildRuleResolver implements BuildRuleResolver {
  private final ForkJoinPool forkJoinPool;
  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  @Nullable private final BuckEventBus eventBus;

  private final BuildRuleResolverMetadataCache metadataCache;
  private final ConcurrentHashMap<BuildTarget, WorkThreadTrackingFuture<BuildRule>> buildRuleIndex;

  public MultiThreadedBuildRuleResolver(
      ForkJoinPool forkJoinPool,
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      BuckEventBus eventBus) {
    this.forkJoinPool = forkJoinPool;
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;
    this.eventBus = eventBus;

    int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);
    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        new BuildRuleResolverMetadataCache(this, this.targetGraph, initialCapacity);
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    return buildRuleIndex
        .values()
        .stream()
        .map(Futures::getUnchecked)
        .collect(MoreCollectors.toImmutableList());
  }

  @Override
  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    return Optional.ofNullable(buildRuleIndex.get(buildTarget))
        .flatMap(
            future ->
                future.isBeingWorkedOnByCurrentThread()
                    ? Optional.empty()
                    : Optional.ofNullable(Futures.getUnchecked(future)));
  }

  @Override
  public BuildRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, BuildRule> mappingFunction) {
    Preconditions.checkState(
        isInForkJoinPool(), "Should only be called while executing in the pool");

    WorkThreadTrackingFuture<BuildRule> future =
        buildRuleIndex.computeIfAbsent(target, wrap(mappingFunction));
    return future.isBeingWorkedOnByCurrentThread()
        ? mappingFunction.apply(target)
        : Futures.getUnchecked(future);
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    return Futures.getUnchecked(
        buildRuleIndex.computeIfAbsent(
            target,
            wrap(key -> buildRuleGenerator.transform(targetGraph, this, targetGraph.get(target)))));
  }

  @Override
  public <T extends BuildRule> T addToIndex(T buildRule) {
    WorkThreadTrackingFuture<BuildRule> future =
        buildRuleIndex.computeIfAbsent(
            buildRule.getBuildTarget(), key -> WorkThreadTrackingFuture.completedFuture(buildRule));
    if (future.isDone()) {
      BuildRule oldValue = Futures.getUnchecked(future);
      Preconditions.checkState(
          oldValue == buildRule,
          "A build rule for this target has already been created: " + oldValue.getBuildTarget());
    }
    return buildRule;
  }

  @Override
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    return metadataCache.requireMetadata(target, metadataClass);
  }

  @Nullable
  @Override
  public BuckEventBus getEventBus() {
    return eventBus;
  }

  private boolean isInForkJoinPool() {
    Thread current = Thread.currentThread();
    return current instanceof ForkJoinWorkerThread
        && ((ForkJoinWorkerThread) current).getPool() == forkJoinPool;
  }

  /**
   * Convert a function returning a value to a function that returns a forked, work-thread-tracked
   * ForkJoinTask that runs the function.
   */
  private <K, V> Function<K, WorkThreadTrackingFuture<V>> wrap(Function<K, V> function) {
    return arg ->
        WorkThreadTrackingFuture.create(
            workThreadTracker -> {
              RecursiveTask<V> task =
                  new RecursiveTask<V>() {
                    @Override
                    protected V compute() {
                      try (Scope ignored = workThreadTracker.start()) {
                        return function.apply(arg);
                      }
                    }
                  };
              return isInForkJoinPool() ? task.fork() : forkJoinPool.submit(task);
            });
  }
}
