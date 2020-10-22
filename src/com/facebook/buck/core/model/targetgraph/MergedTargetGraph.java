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

import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * Target graph version where node is a set of all nodes with the same {@link
 * com.facebook.buck.core.model.UnflavoredBuildTarget}.
 *
 * <p>This utility exists to support legacy configured {@code buck query}, and should not be used
 * for anything else.
 */
public class MergedTargetGraph implements TraversableGraph<MergedTargetNode> {

  private final ImmutableMap<UnflavoredBuildTarget, MergedTargetNode> index;
  private final TraversableGraph<TargetNode<?>> targetGraph;

  public MergedTargetGraph(
      ImmutableMap<UnflavoredBuildTarget, MergedTargetNode> index,
      TraversableGraph<TargetNode<?>> targetGraph) {
    this.index = index;
    this.targetGraph = targetGraph;
  }

  public ImmutableMap<UnflavoredBuildTarget, MergedTargetNode> getIndex() {
    return index;
  }

  @Override
  public Iterable<MergedTargetNode> getNodesWithNoIncomingEdges() {
    ImmutableSet<TargetNode<?>> nodes =
        RichStream.from(targetGraph.getNodesWithNoIncomingEdges())
            .collect(ImmutableSet.toImmutableSet());
    return nodes.stream()
        .map(n -> n.getBuildTarget().getUnflavoredBuildTarget())
        .distinct()
        .map(index::get)
        .filter(n -> nodes.containsAll(n.getNodes()))
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Iterable<MergedTargetNode> getNodesWithNoOutgoingEdges() {
    ImmutableSet<TargetNode<?>> nodes =
        RichStream.from(targetGraph.getNodesWithNoOutgoingEdges())
            .collect(ImmutableSet.toImmutableSet());
    return nodes.stream()
        .map(n -> n.getBuildTarget().getUnflavoredBuildTarget())
        .distinct()
        .map(index::get)
        .filter(n -> nodes.containsAll(n.getNodes()))
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Iterable<MergedTargetNode> getIncomingNodesFor(MergedTargetNode sink) {
    return sink.getNodes().stream()
        .flatMap(n -> RichStream.from(targetGraph.getIncomingNodesFor(n)))
        .map(n -> index.get(n.getBuildTarget().getUnflavoredBuildTarget()))
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Iterable<MergedTargetNode> getOutgoingNodesFor(MergedTargetNode source) {
    return source.getNodes().stream()
        .flatMap(n -> RichStream.from(targetGraph.getOutgoingNodesFor(n)))
        .map(n -> index.get(n.getBuildTarget().getUnflavoredBuildTarget()))
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Iterable<MergedTargetNode> getNodes() {
    return index.values();
  }

  /** Group nodes by {@link UnflavoredBuildTarget}. */
  public static MergedTargetGraph merge(TraversableGraph<TargetNode<?>> targetGraph) {
    return new MergedTargetGraph(MergedTargetNode.group(targetGraph.getNodes()), targetGraph);
  }
}
