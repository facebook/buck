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

package com.facebook.buck.core.graph.transformation.composition;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class ComposedWrapperComputationTest {

  @Test
  public void composedComputationReturnsCorrectPreliminaryDeps() {
    ComposedComputation<LongNode, LongNode> computation =
        new ComposedWrapperComputation<>(LongNode.class, LongNode.IDENTIFIER);

    assertEquals(
        ImmutableSet.of(LongNode.of(1)),
        computation.discoverPreliminaryDeps(ComposedKey.of(LongNode.of(1), LongNode.class)));
  }

  @Test
  public void composedComputationReturnsCorrectDeps() throws Exception {
    FakeComputationEnvironment environment = new FakeComputationEnvironment(ImmutableMap.of());

    ComposedComputation<LongMultNode, LongMultNode> computation =
        new ComposedWrapperComputation<>(LongMultNode.class, LongMultNode.IDENTIFIER);

    assertEquals(
        ImmutableSet.of(),
        computation.discoverDeps(
            ComposedKey.of(LongMultNode.of(2), LongMultNode.class), environment));
  }

  @Test
  public void composedComputationTransformsProperly() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(ImmutableMap.of(LongNode.of(1), LongNode.of(1)));

    ComposedComputation<LongNode, LongNode> computation =
        new ComposedWrapperComputation<>(LongNode.class, LongNode.IDENTIFIER);

    assertEquals(
        ComposedResult.of(ImmutableMap.of(LongNode.of(1), LongNode.of(1))),
        computation.transform(ComposedKey.of(LongNode.of(1), LongNode.class), environment));
  }
}
