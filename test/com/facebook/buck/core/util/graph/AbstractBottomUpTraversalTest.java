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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AbstractBottomUpTraversalTest {

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
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<String>();
    Set<String> nodes = ImmutableSet.of("A", "B", "C", "D", "E", "F");
    for (String node : nodes) {
      mutableGraph.addNode(node);
    }
    mutableGraph.addEdge("A", "B");
    mutableGraph.addEdge("A", "C");
    mutableGraph.addEdge("B", "D");
    mutableGraph.addEdge("B", "E");
    mutableGraph.addEdge("C", "D");
    mutableGraph.addEdge("C", "E");
    mutableGraph.addEdge("D", "F");
    mutableGraph.addEdge("E", "F");

    DirectedAcyclicGraph<String> immutableGraph = new DirectedAcyclicGraph<>(mutableGraph);

    List<String> visitedNodes = new LinkedList<>();
    AbstractBottomUpTraversal<String, RuntimeException> traversal =
        new AbstractBottomUpTraversal<String, RuntimeException>(immutableGraph) {

          @Override
          public void visit(String node) {
            visitedNodes.add(node);
          }
        };
    traversal.traverse();

    assertEquals("F", visitedNodes.get(0));
    assertEquals(ImmutableSet.of("D", "E"), ImmutableSet.copyOf(visitedNodes.subList(1, 3)));
    assertEquals(ImmutableSet.of("B", "C"), ImmutableSet.copyOf(visitedNodes.subList(3, 5)));
    assertEquals("A", visitedNodes.get(5));

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
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<String>();
    Set<String> nodes = ImmutableSet.of("A", "V", "W", "X", "Y", "Z");
    for (String node : nodes) {
      mutableGraph.addNode(node);
    }
    mutableGraph.addEdge("V", "W");
    mutableGraph.addEdge("W", "X");
    mutableGraph.addEdge("X", "Y");
    mutableGraph.addEdge("Y", "Z");
    mutableGraph.addEdge("V", "A");

    DirectedAcyclicGraph<String> immutableGraph = new DirectedAcyclicGraph<String>(mutableGraph);

    Map<String, Integer> visitedNodes = new HashMap<>();
    AbstractBottomUpTraversal<String, RuntimeException> traversal =
        new AbstractBottomUpTraversal<String, RuntimeException>(immutableGraph) {

          @Override
          public void visit(String node) {
            visitedNodes.put(node, visitedNodes.size());
          }
        };
    traversal.traverse();

    assertTrue(visitedNodes.get("A") < visitedNodes.get("V"));
    assertTrue(visitedNodes.get("W") < visitedNodes.get("V"));
    assertTrue(visitedNodes.get("X") < visitedNodes.get("W"));
    assertTrue(visitedNodes.get("Y") < visitedNodes.get("X"));
    assertTrue(visitedNodes.get("Z") < visitedNodes.get("Y"));
    assertEquals(
        "V should be visited last, after all of its dependencies.", 5, (int) visitedNodes.get("V"));

    assertEquals(nodes, visitedNodes.keySet());
  }
}
