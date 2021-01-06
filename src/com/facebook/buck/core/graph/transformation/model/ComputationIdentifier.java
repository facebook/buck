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
 * An Identifier for this {@link ComputeKey}. The identifier is used to uniquely identify a {@link
 * ComputeKey} to its corresponding {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation}, so that all instances of a {@link
 * ComputeKey} can look up the corresponding {@link
 * com.facebook.buck.core.graph.transformation.GraphComputation} correctly.
 */
public interface ComputationIdentifier<ResultType extends ComputeResult> {

  /**
   * @return the class of the result of the {@link
   *     com.facebook.buck.core.graph.transformation.GraphComputation}
   */
  Class<ResultType> getResultTypeClass();
}
