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

import com.facebook.buck.core.graph.transformation.ChildrenAdder.LongNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.MutableGraph;
import org.immutables.value.Value;

/**
 * Demonstration of usage of {@link GraphTransformer}.
 *
 * <p>This returns the value of the sum of its input graph's children and itself. For the above
 * graph in {@code graph}, operating on the root would result in 19.
 */
class ChildrenAdder implements GraphTransformer<LongNode, LongNode> {

  @Value.Immutable(builder = false, copy = false, prehash = true)
  public abstract static class LongNode implements ComputeKey<LongNode>, ComputeResult {
    @Value.Parameter
    public abstract long get();

    @Override
    public Class<? extends ComputeKey<?>> getKeyClass() {
      return LongNode.class;
    }
  }

  private final MutableGraph<LongNode> input;

  public ChildrenAdder(MutableGraph<LongNode> input) {
    this.input = input;
  }

  @Override
  public Class<LongNode> getKeyClass() {
    return LongNode.class;
  }

  @Override
  public LongNode transform(LongNode key, TransformationEnvironment env) {
    return ImmutableLongNode.of(
        key.get() + env.getDeps(LongNode.class).values().stream().mapToLong(LongNode::get).sum());
  }

  @Override
  public ImmutableSet<LongNode> discoverDeps(LongNode key, TransformationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode key) {
    return ImmutableSet.copyOf(input.successors(key));
  }
}
