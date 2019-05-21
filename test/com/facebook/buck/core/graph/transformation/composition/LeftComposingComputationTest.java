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
import com.facebook.buck.core.graph.transformation.model.ComposedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComposedKey;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedKey;
import com.facebook.buck.core.graph.transformation.model.ImmutableComposedResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.junit.Test;

public class LeftComposingComputationTest {

  @Test
  public void composedComputationReturnsCorrectPreliminaryDeps() {
    ComposedKey<LongNode, LongNode> originKey =
        ImmutableComposedKey.of(ImmutableLongNode.of(1), LongNode.class);

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
            ImmutableComposedKey.of(originKey.getOriginKey(), LongMultNode.class)));
  }

  @Test
  public void composedComputationReturnsCorrectDeps() throws Exception {
    LongNode originKey = ImmutableLongNode.of(1);
    LongNode originResult = ImmutableLongNode.of(2);
    ComposedKey<LongNode, LongNode> originComposedKey =
        ImmutableComposedKey.of(originKey, LongNode.class);

    ImmutableSet<LongMultNode> expectedDeps =
        ImmutableSet.of(ImmutableLongMultNode.of(1), ImmutableLongMultNode.of(2));

    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                originComposedKey,
                ImmutableComposedResult.of(ImmutableMap.of(originKey, originResult))));

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
            ImmutableComposedKey.of(originComposedKey.getOriginKey(), LongMultNode.class),
            environment));
  }

  @Test
  public void composedComputationTransformsProperly() throws Exception {
    FakeComputationEnvironment environment =
        new FakeComputationEnvironment(
            ImmutableMap.of(
                ImmutableComposedKey.of(ImmutableLongNode.of(1), LongNode.class),
                ImmutableComposedResult.of(
                    ImmutableMap.of(
                        ImmutableLongNode.of(1),
                        ImmutableLongNode.of(1),
                        ImmutableLongNode.of(2),
                        ImmutableLongNode.of(2))),
                ImmutableLongMultNode.of(1),
                ImmutableLongMultNode.of(1),
                ImmutableLongMultNode.of(2),
                ImmutableLongMultNode.of(2)));

    Composer<LongNode, LongNode> composer =
        (key, result) -> ImmutableSet.of(ImmutableLongMultNode.of(result.get()));
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
        ImmutableComposedResult.of(
            ImmutableMap.of(
                ImmutableLongMultNode.of(1),
                ImmutableLongMultNode.of(1),
                ImmutableLongMultNode.of(2),
                ImmutableLongMultNode.of(2))),
        computation.transform(
            ImmutableComposedKey.of(ImmutableLongNode.of(1), LongMultNode.class), environment));
  }
}
