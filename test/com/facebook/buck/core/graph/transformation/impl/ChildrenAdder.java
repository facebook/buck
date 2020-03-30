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

package com.facebook.buck.core.graph.transformation.impl;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.MutableGraph;

/**
 * Demonstration of usage of {@link GraphComputation}.
 *
 * <p>This returns the value of the sum of its input graph's children and itself. For the above
 * graph in {@code graph}, operating on the root would result in 19.
 */
public class ChildrenAdder implements GraphComputation<LongNode, LongNode> {

  @BuckStylePrehashedValue
  public abstract static class LongNode implements ComputeKey<LongNode>, ComputeResult {
    public static final ComputationIdentifier<LongNode> IDENTIFIER =
        ClassBasedComputationIdentifier.of(LongNode.class, LongNode.class);

    public abstract long get();

    @Override
    public ComputationIdentifier<LongNode> getIdentifier() {
      return IDENTIFIER;
    }

    public static LongNode of(long get) {
      return ImmutableLongNode.of(get);
    }
  }

  private final MutableGraph<LongNode> input;

  public ChildrenAdder(MutableGraph<LongNode> input) {
    this.input = input;
  }

  @Override
  public ComputationIdentifier getIdentifier() {
    return LongNode.IDENTIFIER;
  }

  @Override
  public LongNode transform(LongNode key, ComputationEnvironment env) {
    return ImmutableLongNode.of(
        key.get()
            + env.getDeps(LongNode.IDENTIFIER).values().stream().mapToLong(LongNode::get).sum());
  }

  @Override
  public ImmutableSet<LongNode> discoverDeps(LongNode key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode key) {
    return ImmutableSet.copyOf(input.successors(key));
  }
}
