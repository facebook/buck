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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

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

  /**
   * Internally, on the first request, Transformation schedules the requested key to be completed,
   * and stores the pending computation in a map with key of ComputeKey. Subsequent requests for the
   * same ComputeKey will reuse the stored pending computation.
   *
   * <p>Due to memory overhead of the pending work, upon completion, the pending work is deleted
   * from the stored map to allow it to be garbage collected. The raw result will be put into the
   * result cache. Subsequent requests will reuse the raw result from the cache directly.
   */
  private static final Logger LOG = Logger.get(DefaultGraphTransformationEngine.class);

  private final GraphTransformer<ComputeKey, ComputeResult> transformer;

  private final Executor executor;

  @VisibleForTesting
  final ConcurrentHashMap<ComputeKey, CompletableFuture<ComputeResult>> computationIndex;

  // for caching the completed results.
  private final GraphEngineCache<ComputeKey, ComputeResult> resultCache;

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability and uses {@link ForkJoinPool#commonPool()} to execute tasks.
   *
   * @param transformer the {@link GraphTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   */
  public DefaultGraphTransformationEngine(
      GraphTransformer<ComputeKey, ComputeResult> transformer, int estimatedNumOps) {
    this(transformer, estimatedNumOps, ForkJoinPool.commonPool());
  }

  /**
   * Constructs a {@link DefaultGraphTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability.
   *
   * @param transformer the {@link GraphTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param executor the custom {@link Executor} the engine uses to execute tasks
   */
  public DefaultGraphTransformationEngine(
      GraphTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      Executor executor) {
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
   * @param executor the custom {@link Executor} the engine uses to execute tasks
   */
  public DefaultGraphTransformationEngine(
      GraphTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      GraphEngineCache<ComputeKey, ComputeResult> cache,
      Executor executor) {
    this.transformer = transformer;
    this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
    this.resultCache = cache;
    this.executor = executor;
  }

  @Override
  public final CompletableFuture<ComputeResult> compute(ComputeKey key) {
    return computeInternal(key);
  }

  @Override
  public final ComputeResult computeUnchecked(ComputeKey key) {
    return Futures.getUnchecked(compute(key));
  }

  @Override
  public final ImmutableMap<ComputeKey, CompletableFuture<ComputeResult>> computeAll(
      Iterable<ComputeKey> keys) {
    return RichStream.from(keys)
        .parallel()
        .map(key -> Maps.immutableEntry(key, compute(key)))
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
  }

  @Override
  public final ImmutableMap<ComputeKey, ComputeResult> computeAllUnchecked(
      Iterable<ComputeKey> keys) {
    return Futures.getUnchecked(collectFutures(computeAll(keys)));
  }

  private CompletableFuture<ComputeResult> computeInternal(ComputeKey key) {
    LOG.verbose("Attempting to load from cache for key: %s", key);
    Optional<ComputeResult> result = resultCache.get(key);
    if (result.isPresent()) {
      return CompletableFuture.completedFuture(result.get());
    }

    return computationIndex
        .computeIfAbsent(
            key,
            mapKey -> {
              // recheck the resultCache in event that the cache got populated while we were waiting
              // to access the computationIndex.
              Optional<ComputeResult> cachedResult = resultCache.get(mapKey);
              if (cachedResult.isPresent()) {
                return CompletableFuture.completedFuture(cachedResult.get());
              }

              LOG.verbose("Result cache miss. Computing transformation for requested key: %s", key);
              return CompletableFuture.supplyAsync(() -> mapKey, executor)
                  .thenComposeAsync(computeKey -> computeDeps(computeKey))
                  .thenApplyAsync(
                      deps ->
                          transformer.transform(
                              mapKey, new DefaultTransformationEnvironment<>(deps)),
                      executor)
                  .thenApplyAsync(
                      computedResult -> {
                        resultCache.put(mapKey, computedResult);
                        return computedResult;
                      },
                      executor);
            })
        .thenApplyAsync(
            computedResult -> {
              // Remove the stored Future so we don't keep a reference to a heavy weight future
              // since the value is already in the resultCache
              computationIndex.remove(key);
              return computedResult;
            },
            executor);
  }

  private CompletableFuture<ImmutableMap<ComputeKey, ComputeResult>> computeDeps(ComputeKey key) {
    Set<ComputeKey> depKeys = transformer.discoverDeps(key);
    ImmutableMap.Builder<ComputeKey, ComputeResult> deps =
        ImmutableMap.builderWithExpectedSize(depKeys.size());
    ImmutableSet.Builder<ComputeKey> missingDepKeys =
        ImmutableSet.builderWithExpectedSize(depKeys.size());
    for (ComputeKey depkey : depKeys) {
      Optional<ComputeResult> depResult = resultCache.get(depkey);
      if (depResult.isPresent()) {
        deps.put(depkey, depResult.get());
      } else {
        missingDepKeys.add(depkey);
      }
    }
    return collectFutures(computeAll(missingDepKeys.build()))
        .thenApplyAsync(
            computedResult -> {
              deps.putAll(computedResult);
              return deps.build();
            });
  }

  final <K, V> CompletableFuture<ImmutableMap<K, V>> collectFutures(
      Map<K, CompletableFuture<V>> toCollect) {
    return CompletableFuture.allOf(toCollect.values().toArray(new CompletableFuture[0]))
        .thenApplyAsync(
            voidType ->
                toCollect
                    .entrySet()
                    .parallelStream()
                    .collect(
                        ImmutableMap.toImmutableMap(
                            Entry::getKey, entry -> entry.getValue().join())),
            executor);
  }
}
