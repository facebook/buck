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

package com.facebook.buck.core.util.graph;

import java.util.function.Predicate;

/**
 * Class that performs a "bottom-up" traversal of a DAG. For any given node, every node to which it
 * has an outgoing edge will be visited before the given node.
 */
public abstract class AbstractBottomUpTraversal<T, E extends Throwable> {

  private final TraversableGraph<T> graph;

  public AbstractBottomUpTraversal(TraversableGraph<T> graph) {
    this.graph = graph;
  }

  public final void traverse() throws E {
    traverse(node -> true);
  }

  /**
   * Perform the traversal.
   *
   * @param shouldExploreChildren Whether or not to explore a particular node's children. Used to
   *     support short circuiting in the traversal.
   * @throws E
   */
  public final void traverse(Predicate<T> shouldExploreChildren) throws E {
    Iterable<T> roots = graph.getNodesWithNoIncomingEdges();
    GraphTraversable<T> graphTraversable = node -> graph.getOutgoingNodesFor(node).iterator();
    try {
      for (T node :
          new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable)
              .traverse(roots, shouldExploreChildren)) {
        visit(node);
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new IllegalStateException(
          "Cycle detected despite graph which was claimed to be a DAG", e);
    }
  }

  public abstract void visit(T node) throws E;
}
