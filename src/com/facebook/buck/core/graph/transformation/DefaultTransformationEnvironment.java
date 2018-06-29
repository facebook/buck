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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * A computation environment that {@link AsyncTransformer} can access. This class provides ability
 * of {@link AsyncTransformer}s to request and execute their dependencies on the engine, without
 * exposing blocking operations.
 */
final class DefaultTransformationEnvironment<ComputeKey, ComputeResult>
    implements TransformationEnvironment<ComputeKey, ComputeResult> {

  private final DefaultAsyncTransformationEngine<ComputeKey, ComputeResult> engine;

  private final Executor executor;

  /**
   * Package protected constructor so only {@link DefaultAsyncTransformationEngine} can create the
   * environment
   *
   * @param engine the {@link DefaultAsyncTransformationEngine} that manages this environment
   * @param executor the {@link Executor} the engine uses to execute tasks
   */
  DefaultTransformationEnvironment(
      DefaultAsyncTransformationEngine<ComputeKey, ComputeResult> engine, Executor executor) {
    this.engine = engine;
    this.executor = executor;
  }

  @Override
  public final CompletionStage<ComputeResult> evaluate(
      ComputeKey key, Function<ComputeResult, ComputeResult> asyncTransformation) {
    return engine.compute(key).thenApplyAsync(asyncTransformation, executor);
  }

  @Override
  public final CompletionStage<ComputeResult> evaluateAll(
      Iterable<ComputeKey> keys,
      Function<ImmutableMap<ComputeKey, ComputeResult>, ComputeResult> asyncTransformation) {
    return collectAsyncAndRunInternal(engine.computeAll(keys), asyncTransformation);
  }

  @Override
  public CompletionStage<ComputeResult> evaluateAllAndCollectAsync(
      Iterable<ComputeKey> computeKeys, AsyncSink<ComputeKey, ComputeResult> sink) {
    return engine
        .collectFutures(
            Maps.transformEntries(
                engine.computeAll(computeKeys),
                (key, value) ->
                    value.thenApplyAsync(
                        partialResult -> {
                          sink.sink(key, partialResult);
                          return new Object();
                        },
                        executor)))
        .thenApplyAsync(ignored -> sink.collect(), executor);
  }

  private CompletionStage<ComputeResult> collectAsyncAndRunInternal(
      Map<ComputeKey, CompletableFuture<ComputeResult>> toCollect,
      Function<ImmutableMap<ComputeKey, ComputeResult>, ComputeResult> thenFunc) {
    return engine.collectFutures(toCollect).thenApplyAsync(thenFunc, executor);
  }
}
