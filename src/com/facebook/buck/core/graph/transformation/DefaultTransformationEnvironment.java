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

/**
 * A computation environment that {@link GraphTransformer} can access. This class provides ability
 * of {@link GraphTransformer}s to access their dependencies.
 */
final class DefaultTransformationEnvironment<ComputeKey, ComputeResult>
    implements TransformationEnvironment<ComputeKey, ComputeResult> {

  private final ImmutableMap<ComputeKey, ComputeResult> deps;

  /**
   * Package protected constructor so only {@link DefaultGraphTransformationEngine} can create the
   * environment
   */
  DefaultTransformationEnvironment(ImmutableMap<ComputeKey, ComputeResult> deps) {
    this.deps = deps;
  }

  @Override
  public ImmutableMap<ComputeKey, ComputeResult> getDeps() {
    return deps;
  }
}
