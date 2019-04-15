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
import com.facebook.buck.core.graph.transformation.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.graph.MutableGraph;
import java.util.stream.LongStream;
import org.immutables.value.Value;

/**
 * Demonstration of usage of {@link GraphComputation}.
 *
 * <p>This returns the value of the product of the nodes children's sum
 *
 * <pre>
 *            1
 *         /  |  \
 *        2  4 <- 5
 *       /
 *      3
 * </pre>
 *
 * For the above, we have f(1) = 1*f(2)*f(4*f(5)*s(2*s(4)*s(5) where s is the children adder
 * function
 */
public class ChildrenSumMultiplier implements GraphComputation<LongMultNode, LongMultNode> {

  @Value.Immutable(builder = false, copy = false, prehash = true)
  public abstract static class LongMultNode implements ComputeKey<LongMultNode>, ComputeResult {
    @Value.Parameter
    public abstract long get();

    @Override
    public Class<? extends ComputeKey<?>> getKeyClass() {
      return LongMultNode.class;
    }
  }

  private final MutableGraph<LongNode> input;

  public ChildrenSumMultiplier(MutableGraph<LongNode> input) {
    this.input = input;
  }

  @Override
  public Class<LongMultNode> getKeyClass() {
    return LongMultNode.class;
  }

  @Override
  public LongMultNode transform(LongMultNode key, ComputationEnvironment env) {
    LongStream sumDeps = env.getDeps(LongNode.class).values().stream().mapToLong(LongNode::get);
    LongStream deps =
        env.getDeps(LongMultNode.class).values().stream().mapToLong(LongMultNode::get);
    return ImmutableLongMultNode.of(
        key.get() * Streams.concat(sumDeps, deps).reduce(1, (a, b) -> a * b));
  }

  @Override
  public ImmutableSet<? extends ComputeKey<?>> discoverDeps(
      LongMultNode key, ComputationEnvironment env) {
    return ImmutableSet.copyOf(
        Iterables.transform(
            input.successors(ImmutableLongNode.of(key.get())),
            node -> ImmutableLongMultNode.of(node.get())));
  }

  @Override
  public ImmutableSet<? extends ComputeKey<?>> discoverPreliminaryDeps(LongMultNode key) {
    return ImmutableSet.copyOf(input.successors(ImmutableLongNode.of(key.get())));
  }
}
