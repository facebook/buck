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

package com.facebook.buck.core.util.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AbstractBottomUpTraversalTest {

  private enum Node implements DependencyStack.Element {
    A,
    B,
    C,
    D,
    E,
    F,
    V,
    W,
    X,
    Y,
    Z,
  }

  @Test
  public void testCrissCrossTraversal() {
    // Build up a graph as follows:
    //     A
    //   /   \
    //  B     C
    //  | \ / |
    //  | / \ |
    //  D     E
    //    \ /
    //     F
    DirectedAcyclicGraph.Builder<Node> graphBuilder = DirectedAcyclicGraph.serialBuilder();
    Set<Node> nodes = ImmutableSet.of(Node.A, Node.B, Node.C, Node.D, Node.E, Node.F);
    for (Node node : nodes) {
      graphBuilder.addNode(node);
    }
    graphBuilder.addEdge(Node.A, Node.B);
    graphBuilder.addEdge(Node.A, Node.C);
    graphBuilder.addEdge(Node.B, Node.D);
    graphBuilder.addEdge(Node.B, Node.E);
    graphBuilder.addEdge(Node.C, Node.D);
    graphBuilder.addEdge(Node.C, Node.E);
    graphBuilder.addEdge(Node.D, Node.F);
    graphBuilder.addEdge(Node.E, Node.F);

    DirectedAcyclicGraph<Node> immutableGraph = graphBuilder.build();

    List<Node> visitedNodes = new LinkedList<>();
    AbstractBottomUpTraversal<Node, RuntimeException> traversal =
        new AbstractBottomUpTraversal<Node, RuntimeException>(immutableGraph) {

          @Override
          public void visit(Node node) {
            visitedNodes.add(node);
          }
        };
    traversal.traverse();

    assertEquals(Node.F, visitedNodes.get(0));
    assertEquals(ImmutableSet.of(Node.D, Node.E), ImmutableSet.copyOf(visitedNodes.subList(1, 3)));
    assertEquals(ImmutableSet.of(Node.B, Node.C), ImmutableSet.copyOf(visitedNodes.subList(3, 5)));
    assertEquals(Node.A, visitedNodes.get(5));

    assertEquals(nodes, ImmutableSet.copyOf(visitedNodes));
  }

  @Test
  public void testNodeNotVisitedBeforeItsDependencies() {
    // Build up a graph as follows:
    //         V
    //        / \
    //       W   A
    //      /
    //     X
    //    /
    //   Y
    //  /
    // Z
    DirectedAcyclicGraph.Builder<Node> graphBuilder = DirectedAcyclicGraph.serialBuilder();
    Set<Node> nodes = ImmutableSet.of(Node.A, Node.V, Node.W, Node.X, Node.Y, Node.Z);
    for (Node node : nodes) {
      graphBuilder.addNode(node);
    }
    graphBuilder.addEdge(Node.V, Node.W);
    graphBuilder.addEdge(Node.W, Node.X);
    graphBuilder.addEdge(Node.X, Node.Y);
    graphBuilder.addEdge(Node.Y, Node.Z);
    graphBuilder.addEdge(Node.V, Node.A);

    DirectedAcyclicGraph<Node> immutableGraph = graphBuilder.build();

    Map<Node, Integer> visitedNodes = new HashMap<>();
    AbstractBottomUpTraversal<Node, RuntimeException> traversal =
        new AbstractBottomUpTraversal<Node, RuntimeException>(immutableGraph) {

          @Override
          public void visit(Node node) {
            visitedNodes.put(node, visitedNodes.size());
          }
        };
    traversal.traverse();

    assertTrue(visitedNodes.get(Node.A) < visitedNodes.get(Node.V));
    assertTrue(visitedNodes.get(Node.W) < visitedNodes.get(Node.V));
    assertTrue(visitedNodes.get(Node.X) < visitedNodes.get(Node.W));
    assertTrue(visitedNodes.get(Node.Y) < visitedNodes.get(Node.X));
    assertTrue(visitedNodes.get(Node.Z) < visitedNodes.get(Node.Y));
    assertEquals(
        "V should be visited last, after all of its dependencies.",
        5,
        (int) visitedNodes.get(Node.V));

    assertEquals(nodes, visitedNodes.keySet());
  }
}
