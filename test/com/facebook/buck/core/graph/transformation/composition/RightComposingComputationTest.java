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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.graph.transformation.impl.ImmutableLongMultNode;
import com.facebook.buck.core.graph.transformation.impl.ImmutableLongNode;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedKey;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class RightComposingComputationTest {

  @Test
  public void composedComputationReturnsCorrectPreliminaryDeps() {
    ComposedKey<LongNode, LongNode> originKey =
        ImmutableComposedKey.of(ImmutableLongNode.of(1), LongNode.class);

    RightComposingComputation.RightComposer<LongNode, LongNode, LongMultNode, LongMultNode>
        composer = (ignored1, ignored2) -> null;
    Transformer<
            ComputeKey<ComposedResult<LongMultNode, LongMultNode>>,
            ComposedResult<LongMultNode, LongMultNode>,
            LongMultNode>
        transformer = ignored -> null;
    ComposedComputation<LongNode, LongMultNode> computation =
        new RightComposingComputation<>(
            LongNode.IDENTIFIER, LongMultNode.class, composer, transformer);

    assertEquals(
        ImmutableSet.of(originKey.getOriginKey()),
        computation.discoverPreliminaryDeps(
            ImmutableComposedKey.of(originKey.getOriginKey(), LongMultNode.class)));
  }

  @Test
  public void composedComputationReturnsCorrectDeps() throws Exception {
    LongNode originKey = ImmutableLongNode.of(1);
    LongNode originResult = ImmutableLongNode.of(2);

    ImmutableSet<ComposedKey<LongMultNode, LongMultNode>> expectedDeps =
        ImmutableSet.of(
            ImmutableComposedKey.of(ImmutableLongMultNode.of(1), LongMultNode.class),
            ImmutableComposedKey.of(ImmutableLongMultNode.of(2), LongMultNode.class));

    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(ImmutableMap.of(originKey, originResult));

    RightComposingComputation.RightComposer<LongNode, LongNode, LongMultNode, LongMultNode>
        composer =
            (key, result) -> {
              assertEquals(originKey, key);
              assertEquals(originResult, result);
              return expectedDeps;
            };
    Transformer<
            ComputeKey<ComposedResult<LongMultNode, LongMultNode>>,
            ComposedResult<LongMultNode, LongMultNode>,
            LongMultNode>
        transformer = ignored -> null;
    ComposedComputation<LongNode, LongMultNode> computation =
        new RightComposingComputation<>(
            LongNode.IDENTIFIER, LongMultNode.class, composer, transformer);

    assertEquals(
        expectedDeps,
        computation.discoverDeps(
            ImmutableComposedKey.of(originKey, LongMultNode.class), environment));
  }

  @Test
  public void composedComputationTransformsProperly() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                ImmutableComposedKey.of(ImmutableLongMultNode.of(1), LongMultNode.class),
                ImmutableComposedResult.of(
                    ImmutableMap.of(
                        ImmutableLongMultNode.of(1),
                        ImmutableLongMultNode.of(1),
                        ImmutableLongMultNode.of(2),
                        ImmutableLongMultNode.of(2))),
                ImmutableLongNode.of(1),
                ImmutableLongNode.of(1)));

    RightComposingComputation.RightComposer<LongNode, LongNode, LongMultNode, LongMultNode>
        composer =
            (key, result) ->
                ImmutableSet.of(
                    ImmutableComposedKey.of(
                        ImmutableLongMultNode.of(result.get()), LongMultNode.class));
    Transformer<
            ComputeKey<ComposedResult<LongMultNode, LongMultNode>>,
            ComposedResult<LongMultNode, LongMultNode>,
            LongMultNode>
        transformer =
            deps -> {
              assertEquals(
                  ImmutableComposedResult.of(
                      ImmutableMap.of(
                          ImmutableLongMultNode.of(1),
                          ImmutableLongMultNode.of(1),
                          ImmutableLongMultNode.of(2),
                          ImmutableLongMultNode.of(2))),
                  deps.get(
                      ImmutableComposedKey.of(ImmutableLongMultNode.of(1), LongMultNode.class)));
              return ImmutableMap.of();
            };

    ComposedComputation<LongNode, LongMultNode> computation =
        new RightComposingComputation<>(
            LongNode.IDENTIFIER, LongMultNode.class, composer, transformer);

    assertEquals(
        ImmutableComposedResult.of(ImmutableMap.of()),
        computation.transform(
            ImmutableComposedKey.of(ImmutableLongNode.of(1), LongMultNode.class), environment));
  }
}
