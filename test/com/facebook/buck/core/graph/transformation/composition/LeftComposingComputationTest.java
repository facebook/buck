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
import com.facebook.buck.core.graph.transformation.model.ComposedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComposedResult;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.junit.Test;

public class LeftComposingComputationTest {

  @Test
  public void composedComputationReturnsCorrectPreliminaryDeps() {
    ComposedKey<LongNode, LongNode> originKey = ComposedKey.of(LongNode.of(1), LongNode.class);

    Composer<LongNode, LongNode> composer = (ignored1, ignored2) -> null;
    Transformer<ComputeKey<ComputeResult>, ComputeResult, LongMultNode> transformer =
        ignored -> null;
    ComposedComputation<LongNode, LongMultNode> computation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongMultNode.class,
            composer,
            transformer);

    assertEquals(
        ImmutableSet.of(originKey),
        computation.discoverPreliminaryDeps(
            ComposedKey.of(originKey.getOriginKey(), LongMultNode.class)));
  }

  @Test
  public void composedComputationReturnsCorrectDeps() throws Exception {
    LongNode originKey = LongNode.of(1);
    LongNode originResult = LongNode.of(2);
    ComposedKey<LongNode, LongNode> originComposedKey = ComposedKey.of(originKey, LongNode.class);

    ImmutableSet<LongMultNode> expectedDeps =
        ImmutableSet.of(LongMultNode.of(1), LongMultNode.of(2));

    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                originComposedKey, ComposedResult.of(ImmutableMap.of(originKey, originResult))));

    Composer<LongNode, LongNode> composer =
        (key, result) -> {
          assertEquals(originKey, key);
          assertEquals(originResult, result);
          return expectedDeps;
        };
    Transformer<ComputeKey<ComputeResult>, ComputeResult, LongMultNode> transformer =
        ignored -> null;
    ComposedComputation<LongNode, LongMultNode> computation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongMultNode.class,
            composer,
            transformer);

    assertEquals(
        expectedDeps,
        computation.discoverDeps(
            ComposedKey.of(originComposedKey.getOriginKey(), LongMultNode.class), environment));
  }

  @Test
  public void composedComputationTransformsProperly() throws Exception {
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

    Composer<LongNode, LongNode> composer =
        (key, result) -> ImmutableSet.of(LongMultNode.of(result.get()));
    Transformer<ComputeKey<ComputeResult>, ComputeResult, LongMultNode> transformer =
        deps ->
            (Map<ComputeKey<LongMultNode>, LongMultNode>)
                (Map<? extends ComputeKey<?>, ? extends ComputeResult>) deps;

    ComposedComputation<LongNode, LongMultNode> computation =
        new LeftComposingComputation<>(
            ComposedComputationIdentifier.of(LongNode.IDENTIFIER, LongNode.class),
            LongMultNode.class,
            composer,
            transformer);

    assertEquals(
        ComposedResult.of(
            ImmutableMap.of(
                LongMultNode.of(1), LongMultNode.of(1), LongMultNode.of(2), LongMultNode.of(2))),
        computation.transform(ComposedKey.of(LongNode.of(1), LongMultNode.class), environment));
  }
}
