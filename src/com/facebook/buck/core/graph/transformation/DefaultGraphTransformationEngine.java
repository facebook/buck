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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
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
 * whenever possible based on {@code ComputeKey.equals()}. Therefore, {@link KeyType} should be
 * immutable, and have deterministic equals. For future perspective, we want to have {@link KeyType}
 * be serializable, so that we can eventually send keys to be computed remotely.
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
public final class DefaultGraphTransformationEngine<
        KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
    implements GraphTransformationEngine<KeyType, ResultType> {

  private static final Logger LOG = Logger.get(DefaultGraphTransformationEngine.class);
  @VisibleForTesting final GraphTransformationEngineImpl<?> impl;

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with a default cache that uses the {@link
   * KeyType} for reusability.
   *
   * @param transformer the transformer
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation to reserve the size of its computation index
   * @param executor the custom {@link DepsAwareExecutor} the engine uses to execute tasks
   */
  public DefaultGraphTransformationEngine(
      GraphTransformer<KeyType, ResultType> transformer,
      int estimatedNumOps,
      DepsAwareExecutor<? super ResultType, ?> executor) {
    this(
        transformer,
        estimatedNumOps,
        // Default Cache is just a ConcurrentHashMap
        new GraphEngineCache<KeyType, ResultType>() {
          private final ConcurrentHashMap<KeyType, ResultType> map =
              new ConcurrentHashMap<>(estimatedNumOps);

          @Override
          public Optional<ResultType> get(KeyType k) {
            return Optional.ofNullable(map.get(k));
          }

          @Override
          public void put(KeyType k, ResultType v) {
            map.put(k, v);
          }
        },
        executor);
  }

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with an internal cache that uses the
   * {@link KeyType} for reusability.
   *
   * @param transformer the {@link GraphTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param cache the cache to store the computed results
   * @param executor the custom {@link DepsAwareExecutor} the engine uses to execute tasks
   */
  @SuppressWarnings("unchecked")
  public DefaultGraphTransformationEngine(
      GraphTransformer<KeyType, ResultType> transformer,
      int estimatedNumOps,
      GraphEngineCache<KeyType, ResultType> cache,
      DepsAwareExecutor<? super ResultType, ?> executor) {
    this.impl =
        new GraphTransformationEngineImpl<>(
            transformer, estimatedNumOps, cache, (DepsAwareExecutor<ResultType, ?>) executor);
  }

  @Override
  public void close() {
    impl.close();
  }

  @Override
  public final Future<ResultType> compute(KeyType key) {
    return impl.compute(key);
  }

  @Override
  public final ResultType computeUnchecked(KeyType key) {
    return Futures.getUnchecked(compute(key));
  }

  @Override
  public final ImmutableMap<KeyType, Future<ResultType>> computeAll(Set<KeyType> keys) {
    return RichStream.from(keys)
        .parallel()
        .map(key -> Maps.immutableEntry(key, compute(key)))
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
  }

  @Override
  public final ImmutableMap<KeyType, ResultType> computeAllUnchecked(Set<KeyType> keys) {
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
  class GraphTransformationEngineImpl<TaskType extends DepsAwareTask<ResultType, TaskType>> {

    private final GraphTransformer<KeyType, ResultType> transformer;

    private final DepsAwareExecutor<ResultType, TaskType> executor;

    @VisibleForTesting final ConcurrentHashMap<KeyType, TaskType> computationIndex;
    // for caching the completed results.
    private final GraphEngineCache<KeyType, ResultType> resultCache;

    /**
     * @param transformer the {@link GraphTransformer} this engine executes
     * @param estimatedNumOps the estimated number of operations this engine will execute given a
     *     computation, to reserve the size of its computation index
     * @param cache the cache to store the computed results
     * @param executor the custom {@link Executor} the engine uses to execute tasks
     */
    private GraphTransformationEngineImpl(
        GraphTransformer<KeyType, ResultType> transformer,
        int estimatedNumOps,
        GraphEngineCache<KeyType, ResultType> cache,
        DepsAwareExecutor<ResultType, TaskType> executor) {
      this.transformer = transformer;
      this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
      this.resultCache = cache;
      this.executor = executor;
    }

    public void close() {
      executor.close();
    }

    private Future<ResultType> compute(KeyType key) {
      LOG.verbose("Attempting to load from cache for key: %s", key);
      Optional<ResultType> result = resultCache.get(key);
      if (result.isPresent()) {
        return CompletableFuture.completedFuture(result.get());
      }

      TaskType task = convertKeyToTask(key);
      return executor.submit(task);
    }

    private TaskType convertKeyToTask(KeyType key) {
      return computationIndex.computeIfAbsent(
          key,
          mapKey -> {
            // recheck the resultCache in event that the cache got populated while we were waiting
            // to access the computationIndex.
            Optional<ResultType> cachedResult = resultCache.get(mapKey);
            if (cachedResult.isPresent()) {
              return executor.createTask(
                  () -> {
                    computationIndex.remove(key);
                    return cachedResult.get();
                  });
            }

            LOG.verbose("Result cache miss. Computing transformation for requested key: %s", key);
            ImmutableMap.Builder<KeyType, Future<ResultType>> depResults = ImmutableMap.builder();
            return executor.createThrowingTask(
                () -> computeForKey(key, collectDeps(depResults.build())),
                MoreSuppliers.memoize(
                    () -> computePreliminaryDepForKey(key, depResults), Exception.class),
                MoreSuppliers.memoize(
                    () -> computeDepsForKey(transformer, key, depResults), Exception.class));
          });
    }

    private ResultType computeForKey(KeyType key, ImmutableMap<KeyType, ResultType> depResults)
        throws Exception {
      ResultType result =
          transformer.transform(key, new DefaultTransformationEnvironment<>(depResults));

      resultCache.put(key, result);
      computationIndex.remove(key);
      return result;
    }

    private ImmutableSet<TaskType> computePreliminaryDepForKey(
        KeyType key, ImmutableMap.Builder<KeyType, Future<ResultType>> depResults)
        throws Exception {
      ImmutableSet<KeyType> preliminaryDepKeys = transformer.discoverPreliminaryDeps(key);
      ImmutableSet.Builder<TaskType> preliminaryDepWorkBuilder =
          ImmutableSet.builderWithExpectedSize(preliminaryDepKeys.size());
      for (KeyType preliminaryDepKey : preliminaryDepKeys) {
        TaskType task = convertKeyToTask(preliminaryDepKey);
        depResults.put(preliminaryDepKey, task.getResultFuture());
        preliminaryDepWorkBuilder.add(task);
      }
      return preliminaryDepWorkBuilder.build();
    }

    private ImmutableSet<TaskType> computeDepsForKey(
        GraphTransformer<KeyType, ResultType> transformer,
        KeyType key,
        ImmutableMap.Builder<KeyType, Future<ResultType>> depResults)
        throws Exception {

      ImmutableSet<KeyType> depKeys =
          transformer.discoverDeps(
              key, new DefaultTransformationEnvironment<>(collectDeps(depResults.build())));

      // task that executes secondary deps, depending on the initial deps
      ImmutableSet.Builder<TaskType> depWorkBuilder =
          ImmutableSet.builderWithExpectedSize(depKeys.size());
      for (KeyType depKey : depKeys) {
        TaskType task = convertKeyToTask(depKey);
        depResults.put(depKey, task.getResultFuture());
        depWorkBuilder.add(task);
      }
      return depWorkBuilder.build();
    }

    private ImmutableMap<KeyType, ResultType> collectDeps(
        ImmutableMap<KeyType, Future<ResultType>> deps) {
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
