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
package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Target graph version where node is a set of all nodes with the same {@link
 * UnflavoredBuildTargetView}.
 *
 * <p>This utility exists to support legacy configured {@code buck query}, and should not be used
 * for anything else.
 */
public class MergedTargetGraph extends DirectedAcyclicGraph<MergedTargetNode> {

  private final ImmutableMap<UnflavoredBuildTargetView, MergedTargetNode> index;

  private MergedTargetGraph(
      MutableDirectedGraph<MergedTargetNode> graph,
      ImmutableMap<UnflavoredBuildTargetView, MergedTargetNode> index) {
    super(graph);
    this.index = index;
  }

  public ImmutableMap<UnflavoredBuildTargetView, MergedTargetNode> getIndex() {
    return index;
  }

  /** Group notes by {@link UnflavoredBuildTargetView}. */
  public static MergedTargetGraph merge(DirectedAcyclicGraph<TargetNode<?>> targetGraph) {
    ImmutableMap<UnflavoredBuildTargetView, MergedTargetNode> index =
        MergedTargetNode.group(targetGraph.getNodes());

    MutableDirectedGraph<MergedTargetNode> graph = new MutableDirectedGraph<>();

    for (MergedTargetNode node : index.values()) {
      graph.addNode(node);
    }

    for (Map.Entry<TargetNode<?>, TargetNode<?>> edge : targetGraph.getOutgoingEdges().entries()) {
      TargetNode<?> source = edge.getKey();
      TargetNode<?> sink = edge.getValue();
      MergedTargetNode mergedSource =
          Preconditions.checkNotNull(
              index.get(source.getBuildTarget().getUnflavoredBuildTarget()),
              "node must exist in index: %s",
              source.getBuildTarget().getUnflavoredBuildTarget());
      MergedTargetNode mergedSink =
          Preconditions.checkNotNull(
              index.get(sink.getBuildTarget().getUnflavoredBuildTarget()),
              "node must exist in index: %s",
              sink.getBuildTarget().getUnflavoredBuildTarget());
      graph.addEdge(mergedSource, mergedSink);
    }

    return new MergedTargetGraph(graph, index);
  }
}
