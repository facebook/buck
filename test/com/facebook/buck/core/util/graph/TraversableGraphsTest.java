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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TraversableGraphsTest {

  @Test
  public void isAcyclicSelfEdge() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();
    graph.addEdge("A", "B");
    graph.addEdge("B", "C");
    graph.addEdge("C", "D");
    graph.addEdge("D", "D");
    assertFalse(TraversableGraphs.isAcyclic(graph.getNodes(), graph::getOutgoingNodesFor));
    assertFalse(
        TraversableGraphs.isAcyclic(
            graph.getNodesWithNoIncomingEdges(), graph::getOutgoingNodesFor));
  }

  @Test
  public void isAcyclicNoCycles() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();
    graph.addEdge("A", "B");
    graph.addEdge("A", "D");
    graph.addEdge("B", "C");
    graph.addEdge("B", "D");
    graph.addEdge("C", "D");
    assertTrue(TraversableGraphs.isAcyclic(graph.getNodes(), graph::getOutgoingNodesFor));
    assertTrue(
        TraversableGraphs.isAcyclic(
            graph.getNodesWithNoIncomingEdges(), graph::getOutgoingNodesFor));
  }

  @Test
  public void isAcyclicNoCyclesDiamond() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();
    graph.addEdge("A", "B");
    graph.addEdge("A", "C");
    graph.addEdge("A", "D");
    graph.addEdge("A", "E");
    graph.addEdge("E", "B");
    assertTrue(TraversableGraphs.isAcyclic(graph.getNodes(), graph::getOutgoingNodesFor));
    assertTrue(
        TraversableGraphs.isAcyclic(
            graph.getNodesWithNoIncomingEdges(), graph::getOutgoingNodesFor));
  }

  @Test
  public void isAcyclicCycle() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();
    graph.addEdge("A", "B");
    graph.addEdge("B", "C");
    graph.addEdge("C", "D");
    graph.addEdge("D", "A");
    assertFalse(TraversableGraphs.isAcyclic(graph.getNodes(), graph::getOutgoingNodesFor));
  }
}
