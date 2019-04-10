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

package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.facebook.buck.util.concurrent.WorkThreadTrackingTask;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Function;

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
public class MultiThreadedActionGraphBuilder extends AbstractActionGraphBuilder {
  private boolean isValid = true;
  private final ForkJoinPool forkJoinPool;

  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final Function<BuildTarget, ToolchainProvider> toolchainProviderResolver;

  private final ActionGraphBuilderMetadataCache metadataCache;
  private final ConcurrentHashMap<BuildTarget, WorkThreadTrackingTask<BuildRule>> buildRuleIndex;

  private final Parallelizer parallelizer =
      new Parallelizer() {
        @Override
        public <T, U> Collection<U> maybeParallelizeTransform(
            Collection<T> items, com.google.common.base.Function<? super T, U> transformer) {
          Preconditions.checkState(isInForkJoinPool());
          Collection<WorkThreadTrackingTask<U>> tasks =
              Collections2.transform(
                  items,
                  item -> {
                    WorkThreadTrackingTask<U> task =
                        new WorkThreadTrackingTask<>(() -> transformer.apply(item));
                    task.fork();
                    return task;
                  });
          /**
           * This parallelizer submits tasks to the fork join pool. However, rather than using the
           * standard fork join pool blocking gets, which only correctly work steal from under
           * specific conditions, we use the new {@link WorkThreadTrackingTask#externalCompute()} to
           * await, which can work steal a task across fork join thread's queues.
           */
          return Collections2.transform(tasks, task -> task.externalCompute());
        }
      };

  public MultiThreadedActionGraphBuilder(
      ForkJoinPool forkJoinPool,
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      CellProvider cellProvider) {
    this.forkJoinPool = forkJoinPool;
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;

    int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);
    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        new ActionGraphBuilderMetadataCache(this, this.targetGraph, initialCapacity);
    this.toolchainProviderResolver =
        target -> cellProvider.getBuildTargetCell(target).getToolchainProvider();
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    Preconditions.checkState(isValid);
    return buildRuleIndex.values().stream()
        .map(Futures::getUnchecked)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    Preconditions.checkState(isValid);
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
    Preconditions.checkState(isValid);
    Preconditions.checkState(
        isInForkJoinPool(), "Should only be called while executing in the pool");

    WorkThreadTrackingTask<BuildRule> future =
        buildRuleIndex.computeIfAbsent(target, wrap(mappingFunction));

    if (future.isBeingWorkedOnByCurrentThread()) {

      BuildRule rule = mappingFunction.apply(target);
      future.complete(rule);
      return rule;
    } else {
      return Futures.getUnchecked(future);
    }
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    Preconditions.checkState(isValid);
    return Futures.getUnchecked(
        buildRuleIndex.computeIfAbsent(
            target,
            wrap(
                key ->
                    buildRuleGenerator.transform(
                        toolchainProviderResolver.apply(target),
                        targetGraph,
                        this,
                        targetGraph.get(target)))));
  }

  /** Please use {@code computeIfAbsent} instead */
  @Deprecated
  @Override
  public <T extends BuildRule> T addToIndex(T buildRule) {
    Preconditions.checkState(isValid);
    buildRuleIndex.compute(
        buildRule.getBuildTarget(),
        (key, existing) -> {
          if (existing != null) {
            if (existing.isDone()) {
              BuildRule oldValue = Futures.getUnchecked(existing);
              Preconditions.checkState(
                  oldValue == buildRule,
                  "A build rule for this target has already been created: "
                      + oldValue.getBuildTarget());
            } else {
              // If a future already exist and is incomplete, complete it. This supports cases where
              // the construction of a rule is delegated to another rule, which actually constructs
              // the first rule. (Ex: flavors of AndroidBinary)
              // Ex: requireRule(foo)     -> addToIndex(foo#bar) -> return foo
              //     requireRule(foo#bar) -> requireRule(foo)    -> return getRule(foo#bar)
              existing.complete(buildRule);
            }
            return existing;
          } else {
            return WorkThreadTrackingTask.completed(buildRule);
          }
        });
    return buildRule;
  }

  @Override
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    Preconditions.checkState(isValid);
    return metadataCache.requireMetadata(target, metadataClass);
  }

  @Override
  public Parallelizer getParallelizer() {
    Preconditions.checkState(isValid);
    return parallelizer;
  }

  @Override
  public void invalidate() {
    isValid = false;
    buildRuleIndex.clear();
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
  private Function<BuildTarget, WorkThreadTrackingTask<BuildRule>> wrap(
      Function<BuildTarget, BuildRule> function) {
    return arg ->
        forkOrSubmit(
            new WorkThreadTrackingTask<>(
                () -> {
                  BuildRule rule = function.apply(arg);
                  checkRuleIsBuiltForCorrectTarget(arg, rule);
                  return rule;
                }));
  }

  private <T> WorkThreadTrackingTask<T> forkOrSubmit(WorkThreadTrackingTask<T> task) {
    if (isInForkJoinPool()) {
      task.fork();
    } else {
      forkJoinPool.submit(task);
    }
    return task;
  }
}
