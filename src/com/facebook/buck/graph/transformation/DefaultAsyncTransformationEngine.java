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

package com.facebook.buck.graph.transformation;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Transformation engine that transforms supplied ComputeKey into ComputeResult via {@link
 * AsyncTransformer}. This engine is able to asynchronously run graph based computation, reusing
 * results when possible. Note that the computation graph must be an acyclic graph.
 *
 * <p>This engine is able to deal with dependencies in the computation graph by having Transformer
 * request dependent results of other transformations through {@link
 * TransformationEnvironment#evaluate(Object, Function)}.
 *
 * <p>The transformation is incremental, so cached portions of the transformation will be used
 * whenever possible based on {@code ComputeKey.equals()}. Therefore, {@link ComputeKey} should be
 * immutable, and have deterministic equals. For future perspective, we want to have {@link
 * ComputeKey} be serializable, so that we can eventually send keys to be computed remotely.
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
 * <p>Currently, we only use the engine for {@link com.facebook.buck.rules.TargetGraph} to {@link
 * com.facebook.buck.rules.ActionGraph}, but theoretically this can be extended to work with any
 * computation.
 */
public final class DefaultAsyncTransformationEngine<ComputeKey, ComputeResult>
    implements AsyncTransformationEngine<ComputeKey, ComputeResult> {

  private static final Logger LOG = Logger.get(DefaultAsyncTransformationEngine.class);

  private final AsyncTransformer<ComputeKey, ComputeResult> transformer;

  private final ConcurrentHashMap<ComputeKey, CompletableFuture<ComputeResult>> computationIndex;

  public DefaultAsyncTransformationEngine(
      AsyncTransformer<ComputeKey, ComputeResult> transformer, int estimatedNumOps) {
    this.transformer = transformer;
    this.computationIndex = new ConcurrentHashMap<>(estimatedNumOps);
  }

  @Override
  public final CompletableFuture<ComputeResult> compute(ComputeKey key) {
    return computeWithEnvironment(
        key, new DefaultTransformationEnvironment<ComputeKey, ComputeResult>(this));
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
    LOG.verbose("Computing transformation for requested key: %s", key);
    return computationIndex.computeIfAbsent(
        key,
        mapKey -> {
          return CompletableFuture.supplyAsync(() -> mapKey)
              .thenComposeAsync(computeKey -> transformer.transform(computeKey, env));
        });
  }

  static final <K, V> CompletableFuture<ImmutableMap<K, V>> collectFutures(
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
                            Entry::getKey, entry -> entry.getValue().join())));
  }
}
