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
 * Transformation engine that transforms supplied ComputeKey into ComputeResult via {@link
 * GraphTransformer}. This engine is able to asynchronously run graph based computation, reusing
 * results when possible. Note that the computation dependency graph must be an acyclic graph.
 *
 * <p>This engine is able to deal with dependencies in the computation graph by having Transformer
 * request dependent results of other transformations through {@link
 * GraphTransformer#discoverDeps(Object)}. The engine guarantees that all dependencies are completed
 * before performing the transformation.
 *
 * <p>Transformations also should never block waiting for each other in any manner. If required to
 * wait, the transformation must declare it through {@link GraphTransformer#discoverDeps(Object)}
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
 * GraphTransformer#discoverDeps(Object)} and {@link GraphTransformer#transform(Object,
 * TransformationEnvironment)} methods.
 */
public final class DefaultGraphTransformationEngine<ComputeKey, ComputeResult>
    implements GraphTransformationEngine<ComputeKey, ComputeResult> {

  private static final Logger LOG = Logger.get(DefaultGraphTransformationEngine.class);
  @VisibleForTesting final GraphTransformationEngineImpl<?> impl;

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with a default cache that uses the {@link
   * ComputeKey} for reusability.
   *
   * @param transformer the transformer
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation to reserve the size of its computation index
   * @param executor the custom {@link DepsAwareExecutor} the engine uses to execute tasks
   */
  public DefaultGraphTransformationEngine(
      GraphTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      DepsAwareExecutor<? super ComputeResult, ?> executor) {
    this(
        transformer,
        estimatedNumOps,
        // Default Cache is just a ConcurrentHashMap
        new GraphEngineCache<ComputeKey, ComputeResult>() {
          private final ConcurrentHashMap<ComputeKey, ComputeResult> map =
              new ConcurrentHashMap<>(estimatedNumOps);

          @Override
          public Optional<ComputeResult> get(ComputeKey k) {
            return Optional.ofNullable(map.get(k));
          }

          @Override
          public void put(ComputeKey k, ComputeResult v) {
            map.put(k, v);
          }
        },
        executor);
  }

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability.
   *
   * @param transformer the {@link GraphTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param cache the cache to store the computed results
   * @param executor the custom {@link DepsAwareExecutor} the engine uses to execute tasks
   */
  @SuppressWarnings("unchecked")
  public DefaultGraphTransformationEngine(
      GraphTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      GraphEngineCache<ComputeKey, ComputeResult> cache,
      DepsAwareExecutor<? super ComputeResult, ?> executor) {
    this.impl =
        new GraphTransformationEngineImpl<>(
            transformer, estimatedNumOps, cache, (DepsAwareExecutor<ComputeResult, ?>) executor);
  }

  @Override
  public void shutdownNow() {
    impl.shutdownNow();
  }

  @Override
  public final Future<ComputeResult> compute(ComputeKey key) {
    return impl.compute(key);
  }

  @Override
  public final ComputeResult computeUnchecked(ComputeKey key) {
    return Futures.getUnchecked(compute(key));
  }

  @Override
  public final ImmutableMap<ComputeKey, Future<ComputeResult>> computeAll(Set<ComputeKey> keys) {
    return RichStream.from(keys)
        .parallel()
        .map(key -> Maps.immutableEntry(key, compute(key)))
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
  }

  @Override
  public final ImmutableMap<ComputeKey, ComputeResult> computeAllUnchecked(Set<ComputeKey> keys) {
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

    private final GraphTransformer<ComputeKey, ComputeResult> transformer;

    private final DepsAwareExecutor<ComputeResult, TaskType> executor;

    @VisibleForTesting final ConcurrentHashMap<ComputeKey, TaskType> computationIndex;
    // for caching the completed results.
    private final GraphEngineCache<ComputeKey, ComputeResult> resultCache;

    /**
     * @param transformer the {@link GraphTransformer} this engine executes
     * @param estimatedNumOps the estimated number of operations this engine will execute given a
     *     computation, to reserve the size of its computation index
     * @param cache the cache to store the computed results
     * @param executor the custom {@link Executor} the engine uses to execute tasks
     */
    private GraphTransformationEngineImpl(
        GraphTransformer<ComputeKey, ComputeResult> transformer,
        int estimatedNumOps,
        GraphEngineCache<ComputeKey, ComputeResult> cache,
        DepsAwareExecutor<ComputeResult, TaskType> executor) {
      this.transformer = transformer;
      this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
      this.resultCache = cache;
      this.executor = executor;
    }

    public void shutdownNow() {
      executor.shutdownNow();
    }

    private Future<ComputeResult> compute(ComputeKey key) {
      LOG.verbose("Attempting to load from cache for key: %s", key);
      Optional<ComputeResult> result = resultCache.get(key);
      if (result.isPresent()) {
        return CompletableFuture.completedFuture(result.get());
      }

      TaskType task = convertKeyToTask(key);
      return executor.submit(task);
    }

    private TaskType convertKeyToTask(ComputeKey key) {
      return computationIndex.computeIfAbsent(
          key,
          mapKey -> {
            // recheck the resultCache in event that the cache got populated while we were waiting
            // to access the computationIndex.
            Optional<ComputeResult> cachedResult = resultCache.get(mapKey);
            if (cachedResult.isPresent()) {
              return executor.createTask(
                  () -> {
                    computationIndex.remove(key);
                    return cachedResult.get();
                  });
            }

            LOG.verbose("Result cache miss. Computing transformation for requested key: %s", key);
            ImmutableMap.Builder<ComputeKey, Future<ComputeResult>> depResults =
                ImmutableMap.builder();
            return executor.createTask(
                () -> computeForKey(key, collectDeps(depResults.build())),
                MoreSuppliers.memoize(
                    () -> computeDepsForKey(transformer.discoverDeps(key), depResults),
                    Exception.class));
          });
    }

    private ComputeResult computeForKey(
        ComputeKey key, ImmutableMap<ComputeKey, ComputeResult> depResults) throws Exception {
      ComputeResult result =
          transformer.transform(key, new DefaultTransformationEnvironment<>(depResults));

      resultCache.put(key, result);
      computationIndex.remove(key);
      return result;
    }

    private ImmutableSet<TaskType> computeDepsForKey(
        Set<ComputeKey> depKeys,
        ImmutableMap.Builder<ComputeKey, Future<ComputeResult>> depResults) {
      ImmutableSet.Builder<TaskType> depWorkBuilder =
          ImmutableSet.builderWithExpectedSize(depKeys.size());
      for (ComputeKey depKey : depKeys) {
        TaskType task = convertKeyToTask(depKey);
        depResults.put(depKey, task.getResultFuture());
        depWorkBuilder.add(task);
      }
      return depWorkBuilder.build();
    }

    private ImmutableMap<ComputeKey, ComputeResult> collectDeps(
        ImmutableMap<ComputeKey, Future<ComputeResult>> deps) {
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
