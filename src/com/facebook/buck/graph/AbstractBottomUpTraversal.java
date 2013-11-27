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
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Queue;
import java.util.Set;

/**
 * Class that performs a "bottom-up" traversal of a DAG. For any given node, every node to which it
 * has an outgoing edge will be visited before the given node.
 */
public abstract class AbstractBottomUpTraversal<T, V> {

  private final TraversableGraph<T> graph;

  private final Set<T> visitedNodes;

  private final Queue<T> nodesToExplore;

  public AbstractBottomUpTraversal(TraversableGraph<T> graph) {
    this.graph = Preconditions.checkNotNull(graph);
    this.visitedNodes = Sets.newHashSet();
    this.nodesToExplore = Lists.newLinkedList();
  }

  public final void traverse() {
    Iterables.addAll(nodesToExplore, graph.getNodesWithNoOutgoingEdges());
    while (!nodesToExplore.isEmpty()) {
      T node = nodesToExplore.remove();
      if (visitedNodes.contains(node)) {
        Preconditions.checkState(false,
            "The queue of nodes to explore should not contain a node that has already been"
            + " visited.");
      }

      visit(node);
      visitedNodes.add(node);

      // Only add a node to the set of nodes to be explored if all the nodes it depends on have
      // been visited already.
      for (T exploreCandidate : graph.getIncomingNodesFor(node)) {
        boolean hasUnexploredDependency = false;
        for (T dep : graph.getOutgoingNodesFor(exploreCandidate)) {
          if (!visitedNodes.contains(dep)) {
            hasUnexploredDependency = true;
            break;
          }
        }
        if (!hasUnexploredDependency) {
          nodesToExplore.add(exploreCandidate);
        }
      }
    }
  }

  public abstract void visit(T node);

  public abstract V getResult();

  protected TraversableGraph<T> getGraph() {
    return graph;
  }
}
