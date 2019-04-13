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
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
 *
 * The implementation parallelizes work via the parallelizer and when it receives a call for
 * multiple things at the same time via requireAllRules or computeAllIfAbsent. For all of these
 * cases, it basically creates a list of Tasks, schedules them, tries to finish them on the current
 * thread and then waiting for them. When a Task is scheduled, we submit a job to the executor
 * service to complete that task.
 *
 * <p>This means that a task that is doing work will only recursively process tasks that it has
 * directly asked for (i.e. stack traces will look nice). In the case where work is done by another
 * thread, stack traces will be captured on that thread and then re-thrown from the thread that
 * requested the work.
 */
public class MultiThreadedActionGraphBuilder extends AbstractActionGraphBuilder {
  // TODO(cjhopman): We could consider rewriting the stack trace for work that throws on the
  //  non-requesting thread. That'd be cool and probably a nice UX, but also probably too
  //  complicated for the benefit.

  private boolean isValid = true;

  private final ListeningExecutorService executor;
  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final Function<BuildTarget, ToolchainProvider> toolchainProviderResolver;

  private final ActionGraphBuilderMetadataCache metadataCache;
  private final ConcurrentHashMap<BuildTarget, Task<BuildRule>> buildRuleIndex;
  private final Parallelizer parallelizer;

  public MultiThreadedActionGraphBuilder(
      ListeningExecutorService executor,
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      CellProvider cellProvider) {
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;

    this.executor = executor;

    int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);
    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        new ActionGraphBuilderMetadataCache(this, this.targetGraph, initialCapacity);
    this.toolchainProviderResolver =
        target -> cellProvider.getBuildTargetCell(target).getToolchainProvider();
    this.parallelizer =
        new Parallelizer() {
          @Override
          public <T, U> Collection<U> maybeParallelizeTransform(
              Collection<T> items, com.google.common.base.Function<? super T, U> transformer) {
            ImmutableList.Builder<U> resultBuilder =
                ImmutableList.builderWithExpectedSize(items.size());
            transformParallel(
                items,
                item -> new Task<>(() -> transformer.apply(item)),
                (ignored, value) -> resultBuilder.add(value));
            return resultBuilder.build();
          }
        };
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    Preconditions.checkState(isValid);
    return buildRuleIndex.values().stream().map(Task::get).collect(ImmutableList.toImmutableList());
  }

  @Override
  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    Preconditions.checkState(isValid);
    return Optional.ofNullable(buildRuleIndex.get(buildTarget))
        .flatMap(
            task ->
                !task.isDone() && task.isBeingWorkedOnByCurrentThread()
                    ? Optional.empty()
                    : Optional.ofNullable(task.get()));
  }

  @Override
  public BuildRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, BuildRule> mappingFunction) {
    Preconditions.checkState(isValid);
    Function<BuildTarget, BuildRule> scopedFunction = withCorrectTargetCheck(mappingFunction);
    Task<BuildRule> task =
        buildRuleIndex.computeIfAbsent(
            target, ignored -> new Task<>(() -> scopedFunction.apply(target)));
    if (task.isBeingWorkedOnByCurrentThread()) {
      BuildRule rule = scopedFunction.apply(target);
      task.forceComplete(rule);
      return rule;
    } else {
      return task.get();
    }
  }

  @Override
  public ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets) {
    ImmutableSortedSet.Builder<BuildRule> resultBuilder = ImmutableSortedSet.naturalOrder();
    transformParallel(
        buildTargets, this::addRequireTask, (ignored, value) -> resultBuilder.add(value));
    return resultBuilder.build();
  }

  @Override
  public ImmutableSortedMap<BuildTarget, BuildRule> computeAllIfAbsent(
      ImmutableMap<BuildTarget, Function<BuildTarget, BuildRule>> mappings) {
    ImmutableSortedMap.Builder<BuildTarget, BuildRule> resultBuilder =
        ImmutableSortedMap.naturalOrder();
    transformParallel(
        mappings.entrySet(),
        entry ->
            buildRuleIndex.computeIfAbsent(
                entry.getKey(),
                ignored ->
                    new Task<>(
                        () -> withCorrectTargetCheck(entry.getValue()).apply(entry.getKey()))),
        (entry, rule) -> resultBuilder.put(entry.getKey(), rule));
    return resultBuilder.build();
  }

  @Override
  public Parallelizer getParallelizer() {
    Preconditions.checkState(isValid);
    return parallelizer;
  }

  private <T, U> void transformParallel(
      Iterable<T> inputs, Function<T, Task<U>> taskFactory, BiConsumer<T, U> consumer) {
    List<Task<U>> tasks = new ArrayList<>();

    for (T item : inputs) {
      // Kick off everything.
      tasks.add(taskFactory.apply(item).schedule(executor));
    }

    for (Task<?> task : tasks) {
      task.tryComplete();
    }

    // All tasks are now either finished or started on some other thread.
    MoreIterables.forEachPair(inputs, tasks, (input, task) -> consumer.accept(input, task.get()));
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    Preconditions.checkState(isValid);
    return addRequireTask(target).get();
  }

  /**
   * Requires the rule asynchronously. This isn't part of the ActionGraphBuilder interface and so is
   * only used in places where we know we have a multithreaded one.
   */
  public ListenableFuture<BuildRule> requireRuleFuture(BuildTarget target) {
    Preconditions.checkState(isValid);
    return addRequireTask(target).schedule(executor).future;
  }

  private Task<BuildRule> addRequireTask(BuildTarget target) {
    return buildRuleIndex.computeIfAbsent(
        target,
        ignored ->
            new Task<>(
                () ->
                    withCorrectTargetCheck(
                            ignored2 ->
                                buildRuleGenerator.transform(
                                    toolchainProviderResolver.apply(target),
                                    targetGraph,
                                    this,
                                    targetGraph.get(target)))
                        .apply(target)));
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
            existing.forceComplete(buildRule);
            return existing;
          } else {
            return completed(buildRule);
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
  public void invalidate() {
    isValid = false;
    buildRuleIndex.clear();
  }

  private Function<BuildTarget, BuildRule> withCorrectTargetCheck(
      Function<BuildTarget, BuildRule> function) {
    return target -> {
      BuildRule rule = function.apply(target);
      checkRuleIsBuiltForCorrectTarget(target, rule);
      return rule;
    };
  }

  <V> Task<V> completed(V value) {
    return new Task<>(value);
  }

  private static final class Task<V> {
    @Nullable private volatile Thread workThread;
    // The work to be performed. This field should be set to null when the work no longer need to
    // be performed in order to avoid any lambda captures from being retained.
    private final AtomicReference<Supplier<V>> work;
    private final SettableFuture<V> future;

    private final AtomicBoolean scheduled;

    public Task(V value) {
      this.work = new AtomicReference<>(null);
      this.future = SettableFuture.create();
      this.future.set(value);
      this.scheduled = new AtomicBoolean(true);
    }

    public Task(Supplier<V> work) {
      this.work = new AtomicReference<>(work);
      this.future = SettableFuture.create();
      this.scheduled = new AtomicBoolean();
    }

    private V get() {
      tryComplete();
      return Futures.getUnchecked(future);
    }

    private boolean isBeingWorkedOnByCurrentThread() {
      return Thread.currentThread() == workThread;
    }

    private void tryComplete() {
      tryComplete(Supplier::get);
    }

    private boolean tryComplete(Function<Supplier<V>, V> worker) {
      Supplier<V> acquired = this.work.getAndSet(null);
      if (acquired == null) {
        return false;
      }

      try {
        workThread = Thread.currentThread();
        setFutureValue(worker.apply(acquired));
      } catch (Throwable t) {
        future.setException(t);
      } finally {
        workThread = null;
      }
      return true;
    }

    private boolean isDone() {
      return future.isDone();
    }

    private void forceComplete(V value) {
      if (!tryComplete(ignored -> value)) {
        // If completing the task normally didn't work, forcefully complete it. If the Future is
        // already set, this'll just verify we have a matching value. If it's not set, this'll set
        // it.
        // This supports cases where the construction of a rule is delegated to another rule, which
        // actually constructs the first rule. (Ex: flavors of AndroidBinary).
        // Ex: requireRule(foo)     -> addToIndex(foo#bar) -> return foo
        //     requireRule(foo#bar) -> requireRule(foo)    -> return getRule(foo#bar)
        setFutureValue(value);
      }
    }

    private void setFutureValue(V value) {
      if (!future.set(value)) {
        V oldValue = Futures.getUnchecked(future);
        Preconditions.checkState(
            oldValue == value,
            "A build rule for this target has already been created: " + oldValue);
      }
    }

    private Task<V> schedule(ListeningExecutorService executor) {
      if (!scheduled.getAndSet(true)) {
        executor.execute(this::tryComplete);
      }
      return this;
    }
  }
}
