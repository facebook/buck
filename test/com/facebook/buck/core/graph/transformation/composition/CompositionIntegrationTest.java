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

import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.impl.MyLongNode;
import com.facebook.buck.core.graph.transformation.impl.NoOpComputation;
import com.facebook.buck.core.graph.transformation.impl.NoOpGraphEngineCache;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class CompositionIntegrationTest {

  @Test
  public void graphEngineComputesThreeLeftComposedComputations() {
    NoOpComputation<MyLongNode> noOpComputation1 = new NoOpComputation<>(MyLongNode.IDENTIFIER);
    NoOpComputation<LongNode> noOpComputation2 = new NoOpComputation<>(LongNode.IDENTIFIER);
    NoOpComputation<LongMultNode> noOpComputation3 = new NoOpComputation<>(LongMultNode.IDENTIFIER);

    ComposedComputation<MyLongNode, MyLongNode> composed1 =
        Composition.asComposition(MyLongNode.class, noOpComputation1);
    ComposedComputation<MyLongNode, LongNode> composed2 =
        Composition.composeLeft(
            LongNode.class,
            composed1,
            (KeyComposer<MyLongNode, MyLongNode, LongNode>)
                (key, result) -> ImmutableSet.of(LongNode.of(1)));
    ComposedComputation<MyLongNode, LongMultNode> composed3 =
        Composition.composeLeft(
            LongMultNode.class,
            composed2,
            (KeyComposer<LongNode, LongNode, LongMultNode>)
                (key, result) -> ImmutableSet.of(LongMultNode.of(result.get())));

    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(
                new GraphComputationStage<>(noOpComputation1, new NoOpGraphEngineCache<>()),
                new GraphComputationStage<>(noOpComputation2, new NoOpGraphEngineCache<>()),
                new GraphComputationStage<>(noOpComputation3, new NoOpGraphEngineCache<>()),
                composed1.asStage(),
                composed2.asStage(),
                composed3.asStage()),
            1,
            DefaultDepsAwareExecutor.of(1));

    assertEquals(
        ComposedResult.of(ImmutableMap.of(LongNode.of(1), LongNode.of(1))),
        engine.computeUnchecked(ComposedKey.of(new MyLongNode(), LongNode.class)));
    assertEquals(
        ComposedResult.of(ImmutableMap.of(LongMultNode.of(1), LongMultNode.of(1))),
        engine.computeUnchecked(ComposedKey.of(new MyLongNode(), LongMultNode.class)));
  }

  @Test
  public void graphEngineComputesThreeRightComposedComputations() {
    NoOpComputation<MyLongNode> noOpComputation1 = new NoOpComputation<>(MyLongNode.IDENTIFIER);
    NoOpComputation<LongNode> noOpComputation2 = new NoOpComputation<>(LongNode.IDENTIFIER);
    NoOpComputation<LongMultNode> noOpComputation3 = new NoOpComputation<>(LongMultNode.IDENTIFIER);

    ComposedComputation<LongMultNode, LongMultNode> composed3 =
        Composition.asComposition(LongMultNode.class, noOpComputation3);
    ComposedComputation<LongNode, LongMultNode> composed2 =
        Composition.composeRight(
            LongMultNode.class,
            noOpComputation2,
            (KeyComposer<LongNode, LongNode, LongMultNode>)
                (key, result) -> ImmutableSet.of(LongMultNode.of(result.get())));
    ComposedComputation<MyLongNode, LongMultNode> composed1 =
        Composition.composeRight(
            LongMultNode.class,
            noOpComputation1,
            (KeyComposer<MyLongNode, MyLongNode, LongNode>)
                (key, result) -> ImmutableSet.of(LongNode.of(1)));

    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(
                new GraphComputationStage<>(noOpComputation1, new NoOpGraphEngineCache<>()),
                new GraphComputationStage<>(noOpComputation2, new NoOpGraphEngineCache<>()),
                new GraphComputationStage<>(noOpComputation3, new NoOpGraphEngineCache<>()),
                composed1.asStage(),
                composed2.asStage(),
                composed3.asStage()),
            1,
            DefaultDepsAwareExecutor.of(1));

    assertEquals(
        ComposedResult.of(ImmutableMap.of(LongMultNode.of(1), LongMultNode.of(1))),
        engine.computeUnchecked(ComposedKey.of(new MyLongNode(), LongMultNode.class)));
  }
}
