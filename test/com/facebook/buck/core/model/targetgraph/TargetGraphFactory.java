/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TargetGraphFactory {

  private TargetGraphFactory() {}

  public static TargetGraph newInstance(Iterable<TargetNode<?>> nodes) {
    Map<BuildTarget, TargetNode<?>> builder = new HashMap<>();
    for (TargetNode<?> node : nodes) {
      builder.put(node.getBuildTarget(), node);
      BuildTarget unflavoredTarget = node.getBuildTarget().withoutFlavors();
      if (node.getBuildTarget().isFlavored() && !builder.containsKey(unflavoredTarget)) {
        builder.put(unflavoredTarget, node.withFlavors(ImmutableSet.of()));
      }
    }
    ImmutableMap<BuildTarget, TargetNode<?>> map = ImmutableMap.copyOf(builder);

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    for (TargetNode<?> node : map.values()) {
      graph.addNode(node);
      for (BuildTarget dep : node.getBuildDeps()) {
        graph.addEdge(node, Objects.requireNonNull(map.get(dep), dep::toString));
      }
    }
    return new TargetGraph(graph, map);
  }

  /**
   * Like {@link #newInstance(TargetNode[])} but does not also add a node for unflavored version of
   * the given node.
   */
  public static TargetGraph newInstanceExact(TargetNode<?>... nodes) {
    Map<BuildTarget, TargetNode<?>> builder = new HashMap<>();
    for (TargetNode<?> node : nodes) {
      builder.put(node.getBuildTarget(), node);
    }
    ImmutableMap<BuildTarget, TargetNode<?>> map = ImmutableMap.copyOf(builder);

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    for (TargetNode<?> node : map.values()) {
      graph.addNode(node);
      for (BuildTarget dep : node.getBuildDeps()) {
        graph.addEdge(node, Objects.requireNonNull(map.get(dep), dep::toString));
      }
    }
    return new TargetGraph(graph, map);
  }

  public static TargetGraph newInstance(TargetNode<?>... nodes) {
    return newInstance(ImmutableSet.copyOf(nodes));
  }
}
