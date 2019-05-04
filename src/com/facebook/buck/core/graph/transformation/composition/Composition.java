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

/**
 * Performs a composition over two {@link GraphComputation}s.
 *
 * <p>The first computation is expected to be a composed computation.
 *
 * <p>The composition of two computations C1 that transforms ComposedKey(K1, R1) to
 * ComposedResult(R1) with C2 that transforms K2 to R2 results in a computation C' that transforms
 * ComposedKey(K1, R2) to ComposedResult(R2). This is under a {@link Composer} that takes K1 and R1
 * and returns a set of K2's to be computed.
 */
public interface Composition {

  <
          Key1 extends ComputeKey<Result1>,
          Result1 extends ComputeResult,
          Key2 extends ComputeKey<Result2>,
          Result2 extends ComputeResult>
      GraphComputation<ComposedKey<Key1, Result2>, ComposedResult<Result2>> of(
          GraphComputation<ComposedKey<Key1, Result1>, ComposedResult<Result1>> computation1,
          GraphComputation<Key2, Result2> computation2,
          Composer<Key1, Result1> composer);
}
