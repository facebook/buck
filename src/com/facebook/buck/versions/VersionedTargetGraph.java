/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

public class VersionedTargetGraph extends TargetGraph {

  private final FlavorSearchTargetNodeFinder nodeFinder;

  private VersionedTargetGraph(
      MutableDirectedGraph<TargetNode<?, ?>> graph, FlavorSearchTargetNodeFinder nodeFinder) {
    super(
        graph,
        graph
            .getNodes()
            .stream()
            .collect(MoreCollectors.toImmutableMap(TargetNode::getBuildTarget, n -> n)));
    for (TargetNode<?, ?> node : graph.getNodes()) {
      Preconditions.checkArgument(
          !TargetGraphVersionTransformations.getVersionedNode(node).isPresent());
    }
    this.nodeFinder = nodeFinder;
  }

  @Nullable
  @Override
  protected TargetNode<?, ?> getInternal(BuildTarget target) {
    return nodeFinder.get(target).map(n -> n.withFlavors(target.getFlavors())).orElse(null);
  }

  public static VersionedTargetGraph.Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private final MutableDirectedGraph<TargetNode<?, ?>> graph =
        MutableDirectedGraph.createConcurrent();
    private final Map<BuildTarget, TargetNode<?, ?>> index = new ConcurrentHashMap<>();

    private Builder() {}

    public Builder addNode(BuildTarget baseTarget, TargetNode<?, ?> node) {
      index.put(baseTarget, node);
      graph.addNode(node);
      return this;
    }

    public Builder addEdge(TargetNode<?, ?> src, TargetNode<?, ?> dst) {
      graph.addEdge(src, dst);
      return this;
    }

    public VersionedTargetGraph build() {
      return new VersionedTargetGraph(
          graph, FlavorSearchTargetNodeFinder.of(ImmutableMap.copyOf(index)));
    }
  }
}
