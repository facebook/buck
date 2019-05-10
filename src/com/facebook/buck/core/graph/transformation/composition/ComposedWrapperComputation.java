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

/**
 * A {@link ComposedComputation} that wraps an existing {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation} with compositions.
 *
 * <p>TODO(bobyf): restructure graph engine so that we don't need to create this extra computation
 */
class ComposedWrapperComputation<Key1 extends ComputeKey<Result>, Result extends ComputeResult>
    implements ComposedComputation<Key1, Result> {

  private final ComposedComputationIdentifier<Result> identifier;

  /**
   * @param resultClass the class of the result of the {@link
   *     com.facebook.buck.core.graph.transformation.GraphComputation} we wrap
   * @param baseIdentifier the {@link ComputationIdentifier} of the
   */
  ComposedWrapperComputation(
      Class<Result> resultClass, ComputationIdentifier<Result> baseIdentifier) {
    identifier = ComposedComputationIdentifier.of(baseIdentifier, resultClass);
  }

  @Override
  public ComposedComputationIdentifier<Result> getIdentifier() {
    return identifier;
  }

  @Override
  public ComposedResult<ComputeKey<Result>, Result> transform(
      ComposedKey<Key1, Result> key, ComputationEnvironment env) {
    // TODO(bobyf): figure out how to not do this computation twice

    return ImmutableComposedResult.of(
        ImmutableMap.of(key.getOriginKey(), env.getDep(key.getOriginKey())));
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      ComposedKey<Key1, Result> key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      ComposedKey<Key1, Result> key) {
    return ImmutableSet.of(key.getOriginKey());
  }

  @Override
  public GraphComputationStage<
          ComposedKey<Key1, Result>, ComposedResult<ComputeKey<Result>, Result>>
      asStage() {
    return new GraphComputationStage<>(this, new NoOpGraphEngineCache<>());
  }
}
