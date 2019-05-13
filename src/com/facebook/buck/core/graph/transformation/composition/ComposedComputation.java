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
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.model.ComposedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSet;

/**
 * A {@link GraphComputation} that represents the {@link
 * com.facebook.buck.core.graph.transformation.composition.Composition} of a {@link
 * ComposedComputation} that we can the base computation, a {@link Composer}, and a {@link
 * Transformer}.
 *
 * <p>This computation has a {@link ComposedKey} of the primary computation key and the result of
 * the {@link Transformer}. After the base computation completes, the {@link Composer} will be
 * invoked for each individual result in the {@link ComposedResult} of the base computation,
 * returning a set of dependencies necessary for the transform step. Then {@link Transformer} is
 * invoked with the dependencies.
 *
 * <p>The computation is identified by the base computation it begins at and the target result type
 * class, which means that no two {@link ComposedComputation}s that begins and ends at the same
 * computations can be registered with the graph engine, regardless of what intermediate paths they
 * may differ at.
 *
 * @param <BaseKey> the key type contained in the composed key of the base computation
 * @param <Result2> the result type contained in the composed result of this computation
 */
public interface ComposedComputation<BaseKey extends ComputeKey<?>, Result2 extends ComputeResult>
    extends GraphComputation<
        ComposedKey<BaseKey, Result2>, ComposedResult<ComputeKey<Result2>, Result2>> {

  @Override
  ComposedComputationIdentifier<Result2> getIdentifier();

  @Override
  ComposedResult<ComputeKey<Result2>, Result2> transform(
      ComposedKey<BaseKey, Result2> key, ComputationEnvironment env) throws Exception;

  @Override
  ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      ComposedKey<BaseKey, Result2> key, ComputationEnvironment env) throws Exception;

  @Override
  ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      ComposedKey<BaseKey, Result2> key);

  /**
   * @return a non caching {@link GraphComputationStage} for registering this computation with the
   *     {@link com.facebook.buck.core.graph.transformation.GraphTransformationEngine}
   */
  GraphComputationStage<ComposedKey<BaseKey, Result2>, ComposedResult<ComputeKey<Result2>, Result2>>
      asStage();
}
