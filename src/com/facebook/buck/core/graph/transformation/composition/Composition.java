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
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedKey;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;

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
 * <p>TODO(bobyf): currently composition only builds properly left to right.
 */
public class Composition {

  /**
   * Creates a {@link ComposedComputation} from a base {@link ComposedComputation} used for chaining
   * multiple {@link GraphComputation} together.
   *
   * <p>Chaining computations means to begin at a base computation that transforms K1 to R1, and
   * then using the this method chain a second computation K2 to R2 to create one composed
   * computation of K1 to Composed[R2]. The resulting composed computation can be then used to chain
   * more computations Kn to Rn, until we have computations K1 to Rn. Note that since we create a
   * Composed[Rn], all subsequent compositions are required to wait for the completion of all Rn's.
   * This is a reduce and not ideal for creating computations that resembles trees in its fan out.
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
          KeyBase extends ComputeKey<?>,
          Key1 extends ComputeKey<Result1>,
          Result1 extends ComputeResult,
          Key2 extends ComputeKey<Result2>,
          Result2 extends ComputeResult>
      ComposedComputation<KeyBase, Result2> composeLeft(
          Class<Result2> resultClass,
          ComposedComputation<KeyBase, Result1> baseComputation,
          KeyComposer<Key1, Result1, Key2> composer) {

    return new LeftComposingComputation<>(
        baseComputation.getIdentifier(),
        resultClass,
        composer,
        identity ->
            (Map<ComputeKey<Result2>, Result2>)
                (Map<? extends ComputeKey<?>, ? extends ComputeResult>) identity);
  }

  /**
   * Creates a {@link ComposedComputation} from a base {@link ComposedComputation} used for chaining
   * multiple {@link GraphComputation} together.
   *
   * <p>Chaining computations means to begin at a base computation that transforms K1 to R1, and
   * then using this method chain a second composed computation composed(K2) to composed(R2) to
   * create one composed computation of K1 to Composed[R2]. The resulting composed computation can
   * be then used to chain more computations Kn to Rn, until we have computations K1 to Rn. Note
   * that for this, we start at the right, which helps create fan-out tree structured graph
   * computation.
   *
   * @param resultClass the result class of this composition
   * @param baseComputation the base computation
   * @param composer a {@link KeyComposer} that takes results of the base computation and determines
   *     how to trigger the computation for the result class desired.
   * @param <KeyIntermediate> the type of the key of the right {@link ComposedComputation}
   * @param <Key1> the type of base computation key
   * @param <Result1> the type of base computation result
   * @param <Key2> the type of the key corresponding to the final result type
   * @param <Result2> the result type of the right {@link ComposedComputation}
   * @return a {@link ComposedComputation} that takes in a composed key from {@link Key1} to {@link
   *     com.facebook.buck.core.graph.transformation.model.ComposedResult} of {@link Result2}
   */
  public static <
          KeyIntermediate extends ComputeKey<?>,
          Key1 extends ComputeKey<Result1>,
          Result1 extends ComputeResult,
          Key2 extends ComputeKey<Result2>,
          Result2 extends ComputeResult>
      ComposedComputation<Key1, Result2> composeRight(
          Class<Result2> resultClass,
          GraphComputation<Key1, Result1> baseComputation,
          KeyComposer<Key1, Result1, KeyIntermediate> composer) {

    return new RightComposingComputation<>(
        baseComputation.getIdentifier(),
        resultClass,
        (key1, result1) -> {
          ImmutableSet.Builder<ComposedKey<KeyIntermediate, Result2>> results =
              ImmutableSet.builder();
          for (KeyIntermediate key2 : composer.transitionWith(key1, result1)) {
            results.add(ImmutableComposedKey.of(key2, resultClass));
          }
          return results.build();
        },
        (Transformer<
                ComputeKey<ComposedResult<Key2, Result2>>, ComposedResult<Key2, Result2>, Result2>)
            deps -> {
              ImmutableMap.Builder<ComputeKey<Result2>, Result2> results = ImmutableMap.builder();
              for (ComposedResult<Key2, Result2> dep : deps.values()) {
                results.putAll(dep.resultMap());
              }
              return results.build();
            });
  }

  /**
   * Creates a {@link ComposedComputation} from a normal {@link GraphComputation} for performing
   * compositions. The composition created is just a wrapper around the existing {@link
   * GraphComputation} and doesn't do anything.
   *
   * @param resultClass the class of the result of the wrapped computation
   * @param baseComputation the computation to wrap as {@link ComposedComputation}
   * @param <Key1> the key type of the computation
   * @param <Result1> the result type of the computation
   * @return a {@link ComposedComputation} wrapping the given {@link GraphComputation}
   */
  public static <Key1 extends ComputeKey<Result1>, Result1 extends ComputeResult>
      ComposedComputation<Key1, Result1> asComposition(
          Class<Result1> resultClass, GraphComputation<Key1, Result1> baseComputation) {

    return new ComposedWrapperComputation<>(resultClass, baseComputation.getIdentifier());
  }
}
