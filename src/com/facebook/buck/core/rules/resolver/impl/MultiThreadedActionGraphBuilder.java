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
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.facebook.buck.util.concurrent.WorkThreadTrackingFuture;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveTask;
import java.util.function.Function;
import java.util.function.Supplier;
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
public class MultiThreadedActionGraphBuilder extends AbstractActionGraphBuilder {
  private boolean isValid = true;
  private final ForkJoinPool forkJoinPool;

  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final Function<BuildTarget, ToolchainProvider> toolchainProviderResolver;

  private final ActionGraphBuilderMetadataCache metadataCache;
  private final ConcurrentHashMap<BuildTarget, Task<BuildRule>> buildRuleIndex;

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
  private Function<BuildTarget, Task<BuildRule>> wrap(Function<BuildTarget, BuildRule> function) {
    return arg ->
        forkOrSubmit(
            new Task<>(
                () -> {
                  BuildRule rule = function.apply(arg);
                  checkRuleIsBuiltForCorrectTarget(arg, rule);
                  return rule;
                }));
  }

  private <T> Task<T> forkOrSubmit(Task<T> task) {
    if (isInForkJoinPool()) {
      task.fork();
    } else {
      forkJoinPool.submit(task);
    }
    return task;
  }

  private static final class Task<V> extends RecursiveTask<V>
      implements WorkThreadTrackingFuture<V> {
    @Nullable private Thread workThread;

    // The work to be performed. This field should be set to null when the work no longer need to
    // be performed in order to avoid any lambda captures from being retained.
    //
    // Synchronization is not required, the ForkJoin framework should prevent any races, and the
    // timing of the value being set to null is unimportant.
    @Nullable private Supplier<V> work;

    public Task(Supplier<V> work) {
      this.work = work;
    }

    @Override
    protected final V compute() {
      try (Scope ignored = () -> workThread = null) {
        workThread = Thread.currentThread();
        // The work function should only be invoked while the task is not complete.
        // This condition should be guaranteed by the ForkJoin framework.
        V result = Objects.requireNonNull(work).get();
        Preconditions.checkState(
            getRawResult() == null || getRawResult() == result,
            "A value for this task has already been created: %s",
            getRawResult());
        return result;
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        work = null;
      }
    }

    @Override
    public final boolean isBeingWorkedOnByCurrentThread() {
      return Thread.currentThread() == workThread;
    }

    @Override
    public void complete(V value) {
      Preconditions.checkState(
          getRawResult() == null || getRawResult() == value,
          "A value for this task has already been created: %s",
          getRawResult());
      super.complete(value);
      workThread = null;
      work = null;
    }

    static <V> Task<V> completed(V value) {
      Task<V> task =
          new Task<>(
              () -> {
                throw new AssertionError("This task should be directly completed.");
              });
      task.complete(value);
      return task;
    }
  }
}
