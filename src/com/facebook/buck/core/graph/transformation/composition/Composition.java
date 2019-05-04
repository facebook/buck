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

import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.Iterables;

/**
 * Performs a composition over two {@link GraphComputation}s.
 *
 * <p>The first computation is expected to be a composed computation.
 *
 * <p>The composition of two computations C1 that transforms ComposedKey(K1, R1) to
 * ComposedResult(R1) with C2 that transforms K2 to R2 results in a computation C' that transforms
 * ComposedKey(K1, R2) to ComposedResult(R2). This is under a {@link Composer} that takes K1 and R1
 * and returns a set of K2's to be computed.
 *
 * <p>TODO(bobyf): currently composition only builds properly left to right. It should be
 * associative.
 */
public class Composition {

  /**
   * Creates a composed computation from a base composed computation used for chaining multiple
   * {@link GraphComputation} together.
   *
   * @param resultClass the result class of this composition
   * @param baseComputation the base computation
   * @param composer a {@link KeyComposer} that takes results of the base computation and determines
   *     how to trigger the computation for the result class desired.
   * @param <Key1> the type of base computation key
   * @param <Result1> the type of base computation result
   * @param <Key2> the type of the second composing computation
   * @param <Result2> the result type of the second composing computation
   * @return a {@link ComposedComputation} that takes in a composed key from {@link Key1} to {@link
   *     com.facebook.buck.core.graph.transformation.model.ComposedResult} of {@link Result2}
   */
  @SuppressWarnings("unchecked")
  public static <
          Key1 extends ComputeKey<Result1>,
          Result1 extends ComputeResult,
          Key2 extends ComputeKey<Result2>,
          Result2 extends ComputeResult>
      ComposedComputation<Key1, Result1, Result2> of(
          Class<Result2> resultClass,
          ComposedComputation<Key1, ?, Result1> baseComputation,
          KeyComposer<Key1, Result1, Key2> composer) {

    return new ComposingComputation<>(
        baseComputation.getIdentifier(),
        resultClass,
        composer,
        identity -> (Result2) Iterables.getOnlyElement(identity.values()));
  }
}
