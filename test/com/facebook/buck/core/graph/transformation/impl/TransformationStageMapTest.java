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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.core.graph.transformation.impl.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TransformationStageMapTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void createAndGetWithSingleStage() {
    GraphComputationStage<?, ?> stage = new FakeComputationStage<>(LongNode.class, LongNode.class);
    ComputationStageMap map = ComputationStageMap.from(ImmutableList.of(stage));

    assertEquals(stage, map.get(ImmutableLongNode.of(1)));
  }

  @Test
  public void createAndGetWithMultipleStage() {
    GraphComputationStage<?, ?> stage1 = new FakeComputationStage<>(LongNode.class, LongNode.class);
    GraphComputationStage<?, ?> stage2 =
        new FakeComputationStage<>(MyLongNode.class, MyLongNode.class);
    ComputationStageMap map = ComputationStageMap.from(ImmutableList.of(stage1, stage2));

    assertEquals(stage1, map.get(ImmutableLongNode.of(1)));
    assertEquals(stage2, map.get(new MyLongNode()));
  }

  @Test
  public void unknownKeyThrowsException() {
    expectedException.expect(VerifyException.class);
    ComputationStageMap map = ComputationStageMap.from(ImmutableList.of());
    map.get(new MyLongNode());
  }

  static class FakeComputationStage<Key extends ComputeKey<Result>, Result extends ComputeResult>
      extends GraphComputationStage<Key, Result> {

    public FakeComputationStage(Class<Key> keyClass, Class<Result> resultClass) {
      super(
          new GraphComputation<Key, Result>() {
            @Override
            public ComputationIdentifier<Result> getIdentifier() {
              return ClassBasedComputationIdentifier.of(keyClass, resultClass);
            }

            @Override
            public Result transform(Key key, ComputationEnvironment env) {
              throw new UnsupportedOperationException();
            }

            @Override
            public ImmutableSet<Key> discoverDeps(Key key, ComputationEnvironment env) {
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<Key> discoverPreliminaryDeps(Key key) {
              return ImmutableSet.of();
            }
          },
          new GraphEngineCache<Key, Result>() {
            @Override
            public Optional<Result> get(Key key) {
              return Optional.empty();
            }

            @Override
            public void put(Key key, Result result) {}
          });
    }
  }
}
