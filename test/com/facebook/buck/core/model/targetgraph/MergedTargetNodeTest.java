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

package com.facebook.buck.core.model.targetgraph;

import static org.junit.Assert.*;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Stream;
import org.junit.Test;

public class MergedTargetNodeTest {

  @Test
  public void group() {
    TargetNode<?> qux =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//foo:qux")).build();
    TargetNode<FakeTargetNodeArg> bar1 =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//foo:bar")).build();
    TargetNode<FakeTargetNodeArg> bar2 =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//foo:bar#baz")).build();
    TargetNode<FakeTargetNodeArg> bar3 =
        FakeTargetNodeBuilder.newBuilder(
                BuildTargetFactory.newInstance("//foo:bar")
                    .getUnconfiguredBuildTarget()
                    .configure(ConfigurationBuildTargetFactoryForTests.newConfiguration("//:p")))
            .build();
    ImmutableMap<UnflavoredBuildTarget, MergedTargetNode> groups =
        MergedTargetNode.group(ImmutableList.of(bar1, bar2, bar3, qux));

    assertEquals(
        ImmutableList.of(qux),
        groups.get(qux.getBuildTarget().getUnflavoredBuildTarget()).getNodes());
    assertEquals(
        Stream.of(bar1, bar2, bar3).sorted().collect(ImmutableList.toImmutableList()),
        groups.get(bar1.getBuildTarget().getUnflavoredBuildTarget()).getNodes());
  }
}
