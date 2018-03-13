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
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.facebook.buck.util.concurrent.WorkThreadTrackingFuture;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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
  private boolean isValid = true;
  private final ForkJoinPool forkJoinPool;

  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  @Nullable private final BuckEventBus eventBus;
  private final CellProvider cellProvider;

  private final BuildRuleResolverMetadataCache metadataCache;
  private final ConcurrentHashMap<BuildTarget, Task<BuildRule>> buildRuleIndex;

  public MultiThreadedBuildRuleResolver(
      ForkJoinPool forkJoinPool,
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      CellProvider cellProvider,
      BuckEventBus eventBus) {
    this.forkJoinPool = forkJoinPool;
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;
    this.eventBus = eventBus;
    this.cellProvider = cellProvider;

    int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);
    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        new BuildRuleResolverMetadataCache(this, this.targetGraph, initialCapacity);
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    Preconditions.checkState(isValid);
    return buildRuleIndex
        .values()
        .stream()
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

    Task<BuildRule> future = buildRuleIndex.computeIfAbsent(target, wrap(mappingFunction));
    return future.isBeingWorkedOnByCurrentThread()
        ? mappingFunction.apply(target)
        : Futures.getUnchecked(future);
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
                        cellProvider, targetGraph, this, targetGraph.get(target)))));
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
            return Task.completed(buildRule);
          }
        });
    return buildRule;
  }

  @Override
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    Preconditions.checkState(isValid);
    return metadataCache.requireMetadata(target, metadataClass);
  }

  @Nullable
  @Override
  public BuckEventBus getEventBus() {
    Preconditions.checkState(isValid);
    return eventBus;
  }

  @Override
  public Parallelizer getParallelizer() {
    Preconditions.checkState(isValid);
    return Parallelizer.PARALLEL;
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
  private <K, V> Function<K, Task<V>> wrap(Function<K, V> function) {
    return arg ->
        forkOrSubmit(
            new Task<V>() {
              @Override
              protected V doCompute() {
                return function.apply(arg);
              }
            });
  }

  private <T> Task<T> forkOrSubmit(Task<T> task) {
    if (isInForkJoinPool()) {
      task.fork();
    } else {
      forkJoinPool.submit(task);
    }
    return task;
  }

  private abstract static class Task<V> extends RecursiveTask<V>
      implements WorkThreadTrackingFuture<V> {
    @Nullable private Thread workThread;

    protected abstract V doCompute() throws Exception;

    @Override
    protected final V compute() {
      try (Scope ignored = () -> workThread = null) {
        workThread = Thread.currentThread();
        return doCompute();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public final boolean isBeingWorkedOnByCurrentThread() {
      return Thread.currentThread() == workThread;
    }

    @Override
    public void complete(V value) {
      super.complete(value);
      workThread = null;
    }

    static <V> Task<V> completed(V value) {
      Task<V> task =
          new Task<V>() {
            @Override
            protected V doCompute() {
              throw new AssertionError("This task should be directly completed.");
            }
          };
      task.complete(value);
      return task;
    }
  }
}
