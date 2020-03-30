/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.graph.transformation.model;

/**
 * This is an interface for all keys to be used in {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation}.
 *
 * <p>Implementation should define a static to expose {@link #getIdentifier()} statically for users
 * and for avoiding recomputation.
 *
 * @param <ResultType> should be the corresponding result type of performing the transformation in
 *     the {@link com.facebook.buck.core.graph.transformation.GraphComputation}
 */
public interface ComputeKey<ResultType extends ComputeResult> {

  /**
   * @return the identifier of this key. This identifier is used to map all keys of the same {@link
   *     ComputationIdentifier} to the same {@link
   *     com.facebook.buck.core.graph.transformation.GraphComputation}.
   */
  ComputationIdentifier<ResultType> getIdentifier();
}
