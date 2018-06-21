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

import com.google.common.graph.MutableGraph;
import java.util.concurrent.CompletionStage;

/**
 * Demonstration of usage of {@link AsyncTransformer}.
 *
 * <p>This returns the value of the sum of its input graph's chidren and itself. For the above graph
 * in {@code graph}, operating on the root would result in 19.
 */
class ChildrenAdder implements AsyncTransformer<Long, Long> {

  private final MutableGraph<Long> input;

  public ChildrenAdder(MutableGraph<Long> input) {
    this.input = input;
  }

  @Override
  public CompletionStage<Long> transform(Long key, TransformationEnvironment<Long, Long> env) {
    Iterable<Long> children = input.successors(key);

    return env.evaluateAll(
        children,
        childValues -> key + childValues.values().parallelStream().mapToLong(Long::new).sum());
  }
}
