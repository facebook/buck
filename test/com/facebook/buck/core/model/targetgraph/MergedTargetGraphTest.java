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

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import org.hamcrest.Matchers;
import org.hamcrest.junit.MatcherAssert;
import org.junit.Test;

public class MergedTargetGraphTest {
  @Test
  public void merge() {
    // a    b
    // |    |
    // c1   c2
    //
    // a    b
    //  \  /
    //   c

    TargetNode<?> a =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//:a")).build();
    TargetNode<?> b =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//:b")).build();
    TargetNode<?> c1 =
        FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//:c"))
            .setDeps(a.getBuildTarget())
            .build();
    TargetNode<?> c2 =
        FakeTargetNodeBuilder.newBuilder(
                BuildTargetFactory.newInstance("//:c")
                    .getUnconfiguredBuildTarget()
                    .configure(ConfigurationBuildTargetFactoryForTests.newConfiguration("//:p")))
            .setDeps(b.getBuildTarget())
            .build();

    TargetGraph graph = TargetGraphFactory.newInstance(a, b, c1, c2);
    MergedTargetGraph merged = MergedTargetGraph.merge(graph);

    MergedTargetNode ma = merged.getIndex().get(a.getBuildTarget().getUnflavoredBuildTarget());
    MergedTargetNode mb = merged.getIndex().get(b.getBuildTarget().getUnflavoredBuildTarget());
    MergedTargetNode mc = merged.getIndex().get(c1.getBuildTarget().getUnflavoredBuildTarget());

    MatcherAssert.assertThat(merged.getOutgoingNodesFor(mc), Matchers.containsInAnyOrder(ma, mb));
    MatcherAssert.assertThat(merged.getIncomingNodesFor(ma), Matchers.containsInAnyOrder(mc));
    MatcherAssert.assertThat(merged.getIncomingNodesFor(mb), Matchers.containsInAnyOrder(mc));
  }
}
