/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.graph;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

public class TopologicalSort<T extends Comparable<?>> {

  private TopologicalSort() {}

  public static <T extends Comparable<?>> ImmutableList<T> sort(TraversableGraph<T> graph) {
    try {
      GraphTraversable<T> traversable = node ->
          ImmutableSortedSet.copyOf(graph.getOutgoingNodesFor(node)).iterator();
      Iterable<T> roots = ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges());
      ImmutableList<T> sorted = ImmutableList.copyOf(
          new AcyclicDepthFirstPostOrderTraversal<T>(traversable).traverse(roots));

      int nodeCount = Iterables.size(graph.getNodes());
      Preconditions.checkState(sorted.size() == nodeCount,
          "Expected number of topologically sorted nodes (%s) to be same as number of nodes in " +
              "graph (%s) - maybe there was an undetected cycle", sorted.size(), nodeCount);

      return sorted;
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new IllegalStateException(
          "Cycle detected despite graph which was claimed to be a DAG", e);
    }
  }
}
