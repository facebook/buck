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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;
import java.util.Set;

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
    final Set<String> nodes = ImmutableSet.of("A", "B", "C", "D", "E", "F");
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

    DirectedAcyclicGraph<String> immutableGraph =
        new DefaultDirectedAcyclicGraph<String>(mutableGraph);

    AbstractBottomUpTraversal<String, List<String>> traversal =
        new AbstractBottomUpTraversal<String, List<String>>(immutableGraph) {

      private final List<String> visitedNodes = Lists.newLinkedList();

      @Override
      public void visit(String node) {
        visitedNodes.add(node);
      }

      @Override
      public List<String> getResult() {
        return visitedNodes;
      }
    };
    traversal.traverse();
    List<String> visitedNodes = traversal.getResult();

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
    final Set<String> nodes = ImmutableSet.of("A", "V", "W", "X", "Y", "Z");
    for (String node : nodes) {
      mutableGraph.addNode(node);
    }
    mutableGraph.addEdge("V", "W");
    mutableGraph.addEdge("W", "X");
    mutableGraph.addEdge("X", "Y");
    mutableGraph.addEdge("Y", "Z");
    mutableGraph.addEdge("V", "A");

    DirectedAcyclicGraph<String> immutableGraph =
        new DefaultDirectedAcyclicGraph<String>(mutableGraph);
    AbstractBottomUpTraversal<String, List<String>> traversal =
        new AbstractBottomUpTraversal<String, List<String>>(immutableGraph) {

      private final List<String> visitedNodes = Lists.newLinkedList();

      @Override
      public void visit(String node) {
        visitedNodes.add(node);
      }

      @Override
      public List<String> getResult() {
        return visitedNodes;
      }
    };
    traversal.traverse();

    List<String> visitedNodes = traversal.getResult();
    assertEquals("Z and A have no depdencies, so they should be visited first.",
        ImmutableSet.of("Z", "A"),
        ImmutableSet.copyOf(visitedNodes.subList(0, 2)));
    assertEquals("Y", visitedNodes.get(2));
    assertEquals("X", visitedNodes.get(3));
    assertEquals("W", visitedNodes.get(4));
    assertEquals("V should be visited last, after all of its dependencies.",
        "V",
        visitedNodes.get(5));

    assertEquals(nodes, ImmutableSet.copyOf(visitedNodes));
  }
}
