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

import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder;
import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.impl.ChildrenSumMultiplier.LongMultNode;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.graph.transformation.impl.NoOpComputation;
import com.facebook.buck.core.graph.transformation.model.ComposedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.GraphBuilder;
import java.util.Map;
import org.junit.Test;

public class CompositionTest {

  @Test
  public void leftComposedComputationReturnsCorrectPreliminaryDeps() {
    ComposedComputation<LongNode, LongNode> baseComputation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongNode.class,
            (ignored1, ignored2) -> ImmutableSet.of(),
            identity ->
                (Map<ComputeKey<LongNode>, LongNode>)
                    (Map<? extends ComputeKey<?>, ? extends ComputeResult>) identity);

    ComposedComputation<LongNode, LongMultNode> composedComputation =
        Composition.composeLeft(LongMultNode.class, baseComputation, (ignored1, ignored2) -> null);

    assertEquals(
        ImmutableSet.of(ComposedKey.of(LongNode.of(1), LongNode.class)),
        composedComputation.discoverPreliminaryDeps(
            ComposedKey.of(LongNode.of(1), LongMultNode.class)));
  }

  @Test
  public void leftComposedComputationReturnsCorrectDeps() throws Exception {

    LongNode originKey = LongNode.of(1);
    LongNode originResult = LongNode.of(2);
    ComposedKey<LongNode, LongNode> originComposedKey = ComposedKey.of(originKey, LongNode.class);

    ImmutableSet<LongMultNode> expectedDeps =
        ImmutableSet.of(LongMultNode.of(1), LongMultNode.of(2));

    ComposedComputation<LongNode, LongNode> baseComputation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongNode.class,
            (ignored1, ignored2) -> ImmutableSet.of(),
            identity ->
                (Map<ComputeKey<LongNode>, LongNode>)
                    (Map<? extends ComputeKey<?>, ? extends ComputeResult>) identity);

    KeyComposer<LongNode, LongNode, LongMultNode> composer =
        (key, result) -> {
          assertEquals(originKey, key);
          assertEquals(originResult, result);
          return expectedDeps;
        };

    ComposedComputation<LongNode, LongMultNode> composedComputation =
        Composition.composeLeft(LongMultNode.class, baseComputation, composer);

    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                originComposedKey, ComposedResult.of(ImmutableMap.of(originKey, originResult))));

    assertEquals(
        expectedDeps,
        composedComputation.discoverDeps(
            ComposedKey.of(LongNode.of(1), LongMultNode.class), environment));
  }

  @Test
  public void leftComposedComputationTransformsProperly() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                ComposedKey.of(LongNode.of(1), LongNode.class),
                ComposedResult.of(
                    ImmutableMap.of(
                        LongNode.of(1), LongNode.of(1), LongNode.of(2), LongNode.of(2))),
                LongMultNode.of(1),
                LongMultNode.of(1),
                LongMultNode.of(2),
                LongMultNode.of(2)));

    KeyComposer<LongNode, LongNode, LongMultNode> composer =
        (key, result) -> ImmutableSet.of(LongMultNode.of(result.get()));

    ComposedComputation<LongNode, LongNode> baseComputation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongNode.class,
            (ignored1, ignored2) -> ImmutableSet.of(),
            identity ->
                (Map<ComputeKey<LongNode>, LongNode>)
                    (Map<? extends ComputeKey<?>, ? extends ComputeResult>) identity);

    ComposedComputation<LongNode, LongMultNode> composedComputation =
        Composition.composeLeft(LongMultNode.class, baseComputation, composer);

    assertEquals(
        ComposedResult.of(
            ImmutableMap.of(
                LongMultNode.of(1), LongMultNode.of(1), LongMultNode.of(2), LongMultNode.of(2))),
        composedComputation.transform(
            ComposedKey.of(LongNode.of(1), LongMultNode.class), environment));
  }

  @Test
  public void asCompositionCreatesComposedThatDelegatesToStandardComputation() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(ImmutableMap.of(LongNode.of(1), LongNode.of(1)));

    ComposedComputation<LongNode, LongNode> baseComputation =
        Composition.asComposition(
            LongNode.class, new ChildrenAdder(GraphBuilder.directed().build()));

    assertEquals(
        ImmutableSet.of(LongNode.of(1)),
        baseComputation.discoverPreliminaryDeps(ComposedKey.of(LongNode.of(1), LongNode.class)));

    assertEquals(
        ImmutableSet.of(),
        baseComputation.discoverDeps(ComposedKey.of(LongNode.of(1), LongNode.class), environment));

    assertEquals(
        ComposedResult.of(ImmutableMap.of(LongNode.of(1), LongNode.of(1))),
        baseComputation.transform(ComposedKey.of(LongNode.of(1), LongNode.class), environment));
  }

  @Test
  public void rightComposedComputationReturnsCorrectPreliminaryDeps() {

    ComposedComputation<LongNode, LongMultNode> composedComputation =
        Composition.composeRight(
            LongMultNode.class,
            new NoOpComputation<>(LongNode.IDENTIFIER),
            (ignored1, ignored2) -> null);

    assertEquals(
        ImmutableSet.of(LongNode.of(1)),
        composedComputation.discoverPreliminaryDeps(
            ComposedKey.of(LongNode.of(1), LongMultNode.class)));
  }

  @Test
  public void rightComposedComputationReturnsCorrectDeps() throws Exception {

    LongNode originKey = LongNode.of(1);
    LongNode originResult = LongNode.of(2);

    ImmutableSet<LongMultNode> expectedDeps =
        ImmutableSet.of(LongMultNode.of(1), LongMultNode.of(2));

    KeyComposer<LongNode, LongNode, LongMultNode> composer =
        (key, result) -> {
          assertEquals(originKey, key);
          assertEquals(originResult, result);
          return expectedDeps;
        };

    ComposedComputation<LongNode, LongMultNode> composedComputation =
        Composition.composeRight(
            LongMultNode.class, new NoOpComputation<>(LongNode.IDENTIFIER), composer);

    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(ImmutableMap.of(originKey, originResult));

    assertEquals(
        ImmutableSet.of(
            ComposedKey.of(LongMultNode.of(1), LongMultNode.class),
            ComposedKey.of(LongMultNode.of(2), LongMultNode.class)),
        composedComputation.discoverDeps(
            ComposedKey.of(LongNode.of(1), LongMultNode.class), environment));
  }

  @Test
  public void rightComposedComputationTransformsProperly() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                ComposedKey.of(LongNode.of(1), LongNode.class),
                ComposedResult.of(
                    ImmutableMap.of(
                        LongNode.of(1), LongNode.of(1), LongNode.of(2), LongNode.of(2))),
                LongMultNode.of(1),
                LongMultNode.of(1)));

    KeyComposer<LongMultNode, LongMultNode, LongNode> composer =
        (key, result) -> ImmutableSet.of(LongNode.of(result.get()));

    ComposedComputation<LongMultNode, LongNode> composedComputation =
        Composition.composeRight(
            LongNode.class, new NoOpComputation<>(LongMultNode.IDENTIFIER), composer);

    assertEquals(
        ComposedResult.of(
            ImmutableMap.of(LongNode.of(1), LongNode.of(1), LongNode.of(2), LongNode.of(2))),
        composedComputation.transform(
            ComposedKey.of(LongMultNode.of(1), LongNode.class), environment));
  }
}
