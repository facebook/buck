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
import java.util.Set;

/**
 * Demonstration of usage of {@link GraphTransformer}.
 *
 * <p>This returns the value of the sum of its input graph's chidren and itself. For the above graph
 * in {@code graph}, operating on the root would result in 19.
 */
class ChildrenAdder implements GraphTransformer<Long, Long> {

  private final MutableGraph<Long> input;

  public ChildrenAdder(MutableGraph<Long> input) {
    this.input = input;
  }

  @Override
  public Long transform(Long key, TransformationEnvironment<Long, Long> env) {
    return key + env.getDeps().values().stream().mapToLong(Long::new).sum();
  }

  @Override
  public Set<Long> discoverDeps(Long key) {
    return input.successors(key);
  }
}
