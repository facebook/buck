/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.graph.transformation.composition;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.impl.NoOpGraphEngineCache;
import com.facebook.buck.core.graph.transformation.model.ComposedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.util.Map;

/**
 * An implementation of {@link ComposedComputation} that takes a base {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation}, a {@link Composer}, and {@link
 * Transformer} and returns a new {@link ComposedResult} of {@link Result2} type. This is composing
 * from the right to left. This allows us to define a sequence of transformations K1 -> R1 to Kn ->
 * Rn as one computation K1 -> [Rn] where computation of the next key doesn't require collecting all
 * the results from the prior stages. This lets us perform fan-out tree shaped computations.
 *
 * @param <KeyIntermediate> the key type of the right side {@link ComposedComputation}
 * @param <Key1> the key of the base computation
 * @param <Result1> the result type of the base computation
 * @param <Key2> the key type corresponding to the final result type of this composition
 * @param <Result2> the result type of this computation
 */
public class RightComposingComputation<
        KeyIntermediate extends ComputeKey<?>,
        Key1 extends ComputeKey<Result1>,
        Result1 extends ComputeResult,
        Key2 extends ComputeKey<Result2>,
        Result2 extends ComputeResult>
    implements ComposedComputation<Key1, Result2> {

  private final RightComposer<Key1, Result1, KeyIntermediate, Result2> composer;
  private final Transformer<
          ComputeKey<ComposedResult<Key2, Result2>>, ComposedResult<Key2, Result2>, Result2>
      transformer;

  private final ComposedComputationIdentifier<Result2> identifier;

  /**
   * Creates a composition that runs the base computation, uses the results to derive further
   * dependencies and computes a new result.
   *
   * @param baseComputationIdentifer the {@link ComputationIdentifier} of the base computation.
   * @param resultClass the result type class of the {@link ComposedResult} that this computation
   *     returns
   * @param composer the {@link Composer} that computes the dependencies of this computation based
   *     on the result of the base computation.
   * @param transformer the {@link Transformer} to run on the dependencies to return results of
   *     {@link Result2}
   */
  RightComposingComputation(
      ComputationIdentifier<Result1> baseComputationIdentifer,
      Class<Result2> resultClass,
      RightComposer<Key1, Result1, KeyIntermediate, Result2> composer,
      Transformer<ComputeKey<ComposedResult<Key2, Result2>>, ComposedResult<Key2, Result2>, Result2>
          transformer) {
    this.composer = composer;
    this.transformer = transformer;
    this.identifier = ComposedComputationIdentifier.of(baseComputationIdentifer, resultClass);
  }

  @Override
  public ComposedComputationIdentifier<Result2> getIdentifier() {
    return identifier;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ComposedResult<ComputeKey<Result2>, Result2> transform(
      ComposedKey<Key1, Result2> key, ComputationEnvironment env) throws Exception {
    // TODO(bobyf): figure out how to not do this computation twice
    ImmutableMap.Builder<ComputeKey<Result2>, Result2> resultBuilder = ImmutableMap.builder();

    Map<ComputeKey<Result2>, Result2> values =
        transformer.transform(
            (Map<ComputeKey<ComposedResult<Key2, Result2>>, ComposedResult<Key2, Result2>>)
                Maps.filterKeys(
                    env.getDeps(),
                    composer.transitionWith(key.getOriginKey(), env.getDep(key.getOriginKey()))
                        ::contains));
    resultBuilder.putAll(values);

    return ImmutableComposedResult.of(resultBuilder.build());
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      ComposedKey<Key1, Result2> key, ComputationEnvironment env) throws Exception {
    Result1 results = env.getDep(key.getOriginKey());
    return composer.transitionWith(key.getOriginKey(), results);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      ComposedKey<Key1, Result2> key) {
    return ImmutableSet.of(key.getOriginKey());
  }

  @Override
  public GraphComputationStage<
          ComposedKey<Key1, Result2>, ComposedResult<ComputeKey<Result2>, Result2>>
      asStage() {
    return new GraphComputationStage<>(this, new NoOpGraphEngineCache<>());
  }

  /**
   * Specific {@link Composer} that the right computation is a {@link ComposedComputation}
   *
   * @param <Key1> the left computation key
   * @param <Result1> the left computation result
   * @param <RightKey> the key of the right {@link ComposedComputation}
   * @param <RightResult> the result of the right {@link ComposedComputation}
   */
  @FunctionalInterface
  public interface RightComposer<
          Key1, Result1, RightKey extends ComputeKey<?>, RightResult extends ComputeResult>
      extends Composer<Key1, Result1> {
    @Override
    ImmutableSet<ComposedKey<RightKey, RightResult>> transitionWith(Key1 key, Result1 result)
        throws Exception;
  }
}
