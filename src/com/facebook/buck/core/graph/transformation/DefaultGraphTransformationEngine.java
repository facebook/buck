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

package com.facebook.buck.core.graph.transformation;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Transformation engine that transforms supplied {@link ComputeKey} into {@link ComputeResult} via
 * {@link GraphTransformer}. This engine is able to asynchronously run graph based computation,
 * reusing results when possible. Note that the computation dependency graph must be an acyclic
 * graph.
 *
 * <p>This engine is able to deal with dependencies in the computation graph by having Transformer
 * request dependent results of other transformations through {@link
 * GraphTransformer#discoverPreliminaryDeps(com.facebook.buck.core.graph.transformation.ComputeKey)}
 * and {@link GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)}. The engine
 * guarantees that all dependencies are completed before performing the transformation.
 *
 * <p>This engine allows for multiple stages of transformations via different {@link
 * GraphTransformer}s. These are specified during construction of the engine by supplying multiple
 * {@link GraphTransformationStage}s. Different stages are allowed to depend on other stages, as
 * long as the computation nodes do not form a cyclic dependency.
 *
 * <p>{@link GraphTransformer#discoverPreliminaryDeps(ComputeKey)} will be ran first to discover a
 * set of dependencies based only on the {@link ComputeKey}. The keys are then computed, and the
 * results passed via the {@link TransformationEnvironment} to {@link
 * GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)} for a second stage of
 * dependency discovery, where the dependency discovery can use the dependencies previously
 * specified. The results of dependencies returned via both {@link
 * GraphTransformer#discoverPreliminaryDeps(ComputeKey)} and {@link
 * GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)} will be available for the
 * {@link GraphTransformer#transform(ComputeKey, TransformationEnvironment)} operation.
 *
 * <p>Transformations also should never block waiting for each other in any manner. If required to
 * wait, the transformation must declare it through {@link
 * GraphTransformer#discoverPreliminaryDeps(ComputeKey)} or {@link
 * GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)}
 *
 * <p>The transformation is incremental, so cached portions of the transformation will be used
 * whenever possible based on {@code ComputeKey.equals()}. Therefore, {@link ComputeKey} should be
 * immutable, and have deterministic equals. For future perspective, we want to have {@link
 * ComputeKey} be serializable, so that we can eventually send keys to be computed remotely.
 *
 * <p>A custom cache can be supplied to the engine to cache the computation as desired.
 *
 * <p>Transformations will be applied asynchronously, so independent transformations can be executed
 * in parallel. It is therefore important that transformations are thread safe.
 *
 * <p>By using all callback based operations and queue based operations, this engine will also
 * reduce stack usage, eliminating stack overflow for large graph computations, provided that the
 * {@link GraphTransformer} itself does not stack overflow within its {@link
 * GraphTransformer#discoverPreliminaryDeps(ComputeKey)}, {@link
 * GraphTransformer#discoverDeps(ComputeKey, TransformationEnvironment)}, and {@link
 * GraphTransformer#transform(ComputeKey, TransformationEnvironment)} methods.
 */
public final class DefaultGraphTransformationEngine implements GraphTransformationEngine {

  private static final Logger LOG = Logger.get(DefaultGraphTransformationEngine.class);
  @VisibleForTesting final GraphTransformationEngineImpl<?> impl;

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with the given transformations.
   *
   * @param stages all of the available transformation stages this engine can execute
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param executor the custom {@link DepsAwareExecutor} the engine uses to execute tasks
   */
  @SuppressWarnings("unchecked")
  public DefaultGraphTransformationEngine(
      ImmutableList<GraphTransformationStage<?, ?>> stages,
      int estimatedNumOps,
      DepsAwareExecutor<? super ComputeResult, ?> executor) {
    this.impl =
        new GraphTransformationEngineImpl<>(
            TransformationStageMap.from(stages),
            estimatedNumOps,
            (DepsAwareExecutor<ComputeResult, ?>) executor);
  }

  @Override
  public void close() {
    impl.close();
  }

  @Override
  public final <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      Future<ResultType> compute(KeyType key) {
    return impl.compute(key);
  }

  @Override
  public final <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ResultType computeUnchecked(KeyType key) {
    return Futures.getUnchecked(compute(key));
  }

  @Override
  public final <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ImmutableMap<KeyType, Future<ResultType>> computeAll(Set<KeyType> keys) {
    return RichStream.from(keys)
        .parallel()
        .map(key -> Maps.immutableEntry(key, compute(key)))
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
  }

  @Override
  public final <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ImmutableMap<KeyType, ResultType> computeAllUnchecked(Set<KeyType> keys) {
    return ImmutableMap.copyOf(Maps.transformValues(computeAll(keys), Futures::getUnchecked));
  }

  /**
   * Internal implementation of the {@link DefaultGraphTransformationEngine} to hide some type
   * parameters
   *
   * <p>Internally, on the first request, Transformation schedules the requested key to be
   * completed, and stores the pending computation in a map with key of ComputeKey. Subsequent
   * requests for the same ComputeKey will reuse the stored pending computation.
   *
   * <p>Due to memory overhead of the pending task, upon completion, the pending task is deleted
   * from the stored map to allow it to be garbage collected. The raw result will be put into the
   * result cache. Subsequent requests will reuse the raw result from the cache directly.
   */
  @VisibleForTesting
  class GraphTransformationEngineImpl<TaskType extends DepsAwareTask<ComputeResult, TaskType>> {

    private final TransformationStageMap transformationStageMap;

    private final DepsAwareExecutor<ComputeResult, TaskType> executor;

    @VisibleForTesting
    final ConcurrentHashMap<ComputeKey<? extends ComputeResult>, TaskType> computationIndex;

    /**
     * @param transformationStageMap a map of the key types to the transformation stages
     * @param estimatedNumOps the estimated number of operations this engine will execute given a
     *     computation, to reserve the size of its computation index
     * @param executor the custom {@link Executor} the engine uses to execute tasks
     */
    private GraphTransformationEngineImpl(
        TransformationStageMap transformationStageMap,
        int estimatedNumOps,
        DepsAwareExecutor<ComputeResult, TaskType> executor) {
      this.transformationStageMap = transformationStageMap;
      this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
      this.executor = executor;
    }

    public void close() {
      executor.close();
    }

    @SuppressWarnings("unchecked")
    private <UResultType extends ComputeResult, UKeyType extends ComputeKey<UResultType>>
        Future<UResultType> compute(UKeyType key) {
      LOG.verbose("Attempting to load from cache for key: %s", key);
      GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult> stage =
          transformationStageMap.get(key);
      Optional<? extends ComputeResult> result = stage.getCache().get(key);
      if (result.isPresent()) {
        return CompletableFuture.completedFuture((UResultType) result.get());
      }

      TaskType task = convertKeyToTask(key, stage);
      return (Future<UResultType>) executor.submit(task);
    }

    private TaskType convertKeyToTask(
        ComputeKey<? extends ComputeResult> key,
        GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>
            stage) {
      return computationIndex.computeIfAbsent(
          key,
          mapKey -> {
            // recheck the resultCache in event that the cache got populated while we were waiting
            // to access the computationIndex.
            Optional<? extends ComputeResult> cachedResult = stage.getCache().get(key);
            if (cachedResult.isPresent()) {
              return executor.createTask(
                  () -> {
                    computationIndex.remove(key);
                    return cachedResult.get();
                  });
            }

            ImmutableMap.Builder<ComputeKey<?>, Future<ComputeResult>> depResults =
                ImmutableMap.builder();
            ThrowingSupplier<ImmutableSet<TaskType>, Exception> preliminaryDepsSupplier =
                MoreSuppliers.memoize(
                    () -> computePreliminaryDepForKey(key, stage, depResults), Exception.class);
            ThrowingSupplier<ImmutableSet<TaskType>, Exception> depsSupplier =
                MoreSuppliers.memoize(
                    () -> computeDepsForKey(stage, key, depResults), Exception.class);
            return executor.createThrowingTask(
                () -> computeForKey(key, stage, collectDeps(depResults.build())),
                preliminaryDepsSupplier,
                depsSupplier);
          });
    }

    private ComputeResult computeForKey(
        ComputeKey<? extends ComputeResult> key,
        GraphTransformationStage<ComputeKey<?>, ? extends ComputeResult> stage,
        ImmutableMap<ComputeKey<?>, ComputeResult> depResults)
        throws Exception {
      ComputeResult result = stage.transform(key, new DefaultTransformationEnvironment(depResults));

      computationIndex.remove(key);
      return result;
    }

    private ImmutableSet<TaskType> computePreliminaryDepForKey(
        ComputeKey<? extends ComputeResult> key,
        GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>
            stage,
        ImmutableMap.Builder<ComputeKey<?>, Future<ComputeResult>> depResults)
        throws Exception {
      ImmutableSet<? extends ComputeKey<?>> preliminaryDepKeys =
          stage.getTransformer().discoverPreliminaryDeps(key);
      ImmutableSet.Builder<TaskType> preliminaryDepWorkBuilder =
          ImmutableSet.builderWithExpectedSize(preliminaryDepKeys.size());
      for (ComputeKey<? extends ComputeResult> preliminaryDepKey : preliminaryDepKeys) {
        GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>
            depStage = transformationStageMap.get(preliminaryDepKey);
        convertKeyToTask(preliminaryDepKey, depStage);
      }
      preliminaryDepKeys.forEach(
          preliminaryDepKey -> {
            GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>
                depStage = transformationStageMap.get(preliminaryDepKey);
            TaskType task = convertKeyToTask(preliminaryDepKey, depStage);
            depResults.put(preliminaryDepKey, task.getResultFuture());
            preliminaryDepWorkBuilder.add(task);
          });
      return preliminaryDepWorkBuilder.build();
    }

    private ImmutableSet<TaskType> computeDepsForKey(
        GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>
            stage,
        ComputeKey<? extends ComputeResult> key,
        ImmutableMap.Builder<ComputeKey<?>, Future<ComputeResult>> depResults)
        throws Exception {

      ImmutableSet<? extends ComputeKey<? extends ComputeResult>> depKeys =
          stage
              .getTransformer()
              .discoverDeps(
                  key, new DefaultTransformationEnvironment(collectDeps(depResults.build())));

      // task that executes secondary deps, depending on the initial deps
      ImmutableSet.Builder<TaskType> depWorkBuilder =
          ImmutableSet.builderWithExpectedSize(depKeys.size());
      for (ComputeKey<? extends ComputeResult> depKey : depKeys) {
        TaskType task = convertKeyToTask(depKey, transformationStageMap.get(depKey));
        depResults.put(depKey, task.getResultFuture());
        depWorkBuilder.add(task);
      }
      return depWorkBuilder.build();
    }

    private ImmutableMap<ComputeKey<?>, ComputeResult> collectDeps(
        ImmutableMap<ComputeKey<?>, Future<ComputeResult>> deps) {
      return ImmutableMap.copyOf(
          Maps.transformValues(
              deps,
              futureRes -> {
                Preconditions.checkState(futureRes.isDone());
                return Futures.getUnchecked(futureRes);
              }));
    }
  }
}
