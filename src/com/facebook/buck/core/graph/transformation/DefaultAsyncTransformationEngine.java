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
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * Transformation engine that transforms supplied ComputeKey into ComputeResult via {@link
 * AsyncTransformer}. This engine is able to asynchronously run graph based computation, reusing
 * results when possible. Note that the computation graph must be an acyclic graph.
 *
 * <p>This engine is able to deal with dependencies in the computation graph by having Transformer
 * request dependent results of other transformations through {@link
 * TransformationEnvironment#evaluate(Object, Function)} or {@link
 * TransformationEnvironment#evaluateAll(Iterable, Function)}.
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
 * <p>Transformations also should never block waiting for Futures. Hence, Transformations can only
 * access the Future results through {@link CompletionStage} as opposed to {@link
 * java.util.concurrent.Future}, which only has async methods exposed. It is strongly suggested to
 * use the async versions of all methods on the {@link CompletionStage} to allow Java to perform the
 * transformation in any executor.
 *
 * <p>By using all callback based operations and being tail recursive, this engine will also reduce
 * stack usage, eliminating stack overflow for large graph computations. The {@link
 * TransformationEnvironment} has every method implemented as non-blocking, and returns a Future of
 * the dependency calculation such that if Transformer is implemented to be tail recursive, the
 * whole graph computation will be tail recursive, eliminating stack use.
 *
 * <p>Currently, we only use the engine for {@link
 * com.facebook.buck.core.model.targetgraph.TargetGraph} to {@link
 * com.facebook.buck.core.model.actiongraph.ActionGraph}, but theoretically this can be extended to
 * work with any computation.
 */
public final class DefaultAsyncTransformationEngine<ComputeKey, ComputeResult>
    implements AsyncTransformationEngine<ComputeKey, ComputeResult> {

  /**
   * Internally, on the first request, AsyncTransformation schedules the requested key to be
   * completed via a Future, and stores the Future in a map with key of ComputeKey. Subsequent
   * requests for the same ComputeKey will reuse the stored Future, where async operations are then
   * added to the scheduled Future.
   *
   * <p>Due to memory overhead of the Future, upon Future completion, the future is deleted from the
   * stored map to allow it to be garbage collected. The raw result will be put into the result
   * cache. Subsequent requests will reuse the raw result from the cache directly.
   */
  private static final Logger LOG = Logger.get(DefaultAsyncTransformationEngine.class);

  private final AsyncTransformer<ComputeKey, ComputeResult> transformer;

  private final Executor executor;

  @VisibleForTesting
  final ConcurrentHashMap<ComputeKey, CompletableFuture<ComputeResult>> computationIndex;

  // for caching the completed results.
  private final TransformationEngineCache<ComputeKey, ComputeResult> resultCache;

  /**
   * Constructs a {@link DefaultAsyncTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability and uses {@link ForkJoinPool#commonPool()} to execute tasks.
   *
   * @param transformer the {@link AsyncTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   */
  public DefaultAsyncTransformationEngine(
      AsyncTransformer<ComputeKey, ComputeResult> transformer, int estimatedNumOps) {
    this(transformer, estimatedNumOps, ForkJoinPool.commonPool());
  }

  /**
   * Constructs a {@link DefaultAsyncTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability.
   *
   * @param transformer the {@link AsyncTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param executor the custom {@link Executor} the engine uses to execute tasks
   */
  public DefaultAsyncTransformationEngine(
      AsyncTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      Executor executor) {
    this(
        transformer,
        estimatedNumOps,
        // Default Cache is just a ConcurrentHashMap
        new TransformationEngineCache<ComputeKey, ComputeResult>() {
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
   * Constructs a {@link DefaultAsyncTransformationEngine} with an internal cache that uses the
   * {@link ComputeKey} for reusability.
   *
   * @param transformer the {@link AsyncTransformer} this engine executes
   * @param estimatedNumOps the estimated number of operations this engine will execute given a
   *     computation, to reserve the size of its computation index
   * @param cache the cache to store the computed results
   * @param executor the custom {@link Executor} the engine uses to execute tasks
   */
  public DefaultAsyncTransformationEngine(
      AsyncTransformer<ComputeKey, ComputeResult> transformer,
      int estimatedNumOps,
      TransformationEngineCache<ComputeKey, ComputeResult> cache,
      Executor executor) {
    this.transformer = transformer;
    this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
    this.resultCache = cache;
    this.executor = executor;
  }

  @Override
  public final CompletableFuture<ComputeResult> compute(ComputeKey key) {
    return computeWithEnvironment(key, new DefaultTransformationEnvironment<>(this, executor));
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

  private CompletableFuture<ComputeResult> computeWithEnvironment(
      ComputeKey key, TransformationEnvironment<ComputeKey, ComputeResult> env) {
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
                  .thenComposeAsync(computeKey -> transformer.transform(computeKey, env), executor)
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

  final <K, V> CompletableFuture<ImmutableMap<K, V>> collectFutures(
      Map<K, CompletableFuture<V>> toCollect) {
    return CompletableFuture.allOf(
            toCollect.values().toArray(new CompletableFuture[toCollect.size()]))
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
