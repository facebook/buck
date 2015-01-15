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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.Set;

public class MutableDirectedGraphTest {

  @Test
  public void testCreateNewGraph() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    assertEquals(0, graph.getNodeCount());
    assertEquals(0, graph.getEdgeCount());
    assertFalse(graph.containsNode("A"));
    assertFalse(graph.containsEdge("A", "B"));
    assertTrue(graph.isAcyclic());
    assertEquals(ImmutableSet.<String>of(), graph.getNodes());
  }

  @Test
  public void testCreateGraphWithOneEdge() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    Set<String> nodesView = graph.getNodes();
    assertEquals(ImmutableSet.<String>of(), nodesView);
    graph.addEdge("A", "B");
    assertEquals(2, graph.getNodeCount());
    assertEquals(1, graph.getEdgeCount());
    assertTrue(graph.containsEdge("A", "B"));
    assertEquals(ImmutableSet.of("A"), graph.getNodesWithNoIncomingEdges());
    assertTrue(graph.isAcyclic());
    assertEquals(ImmutableSet.of("A", "B"), nodesView);
  }

  /**
   * Make sure a node can point to itself. Also ensure that when a self-referential edge is removed,
   * the graph is left in the correct state.
   */
  @Test
  public void testAddSelfReferentialEdge() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    graph.addEdge("A", "A");
    assertEquals(1, graph.getNodeCount());
    assertEquals(1, graph.getEdgeCount());
    assertTrue(graph.containsNode("A"));
    assertTrue(graph.containsEdge("A", "A"));
    assertEquals(ImmutableSet.of("A"), graph.getOutgoingNodesFor("A"));
    assertEquals(ImmutableSet.of("A"), graph.getIncomingNodesFor("A"));
    assertTrue(graph.hasIncomingEdges("A"));
    assertFalse(graph.isAcyclic());
    assertEquals(ImmutableSet.of(ImmutableSet.of("A")), graph.findCycles());

    graph.removeNode("A");
    assertEquals(0, graph.getNodeCount());
    assertEquals(0, graph.getEdgeCount());
    assertFalse(graph.containsNode("A"));
    assertFalse(graph.containsEdge("A", "A"));
    assertEquals(ImmutableSet.<String>of(), graph.getOutgoingNodesFor("A"));
    assertEquals(ImmutableSet.<String>of(), graph.getIncomingNodesFor("A"));
    assertTrue(graph.isAcyclic());
  }

  @Test
  public void testIsAcyclic() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    graph.addEdge("A", "B");
    assertTrue(graph.isAcyclic());
    graph.addEdge("B", "C");
    assertTrue(graph.isAcyclic());
    graph.addEdge("C", "A");
    graph.addEdge("D", "E");
    assertFalse("Graph has a cycle: A->B->C", graph.isAcyclic());
    assertEquals(ImmutableSet.of(ImmutableSet.of("A", "B", "C")), graph.findCycles());

    graph.removeEdge("C", "A");
    assertTrue(graph.isAcyclic());
    graph.addEdge("A", "D");
    assertTrue(graph.isAcyclic());
    graph.addEdge("D", "C");
    assertTrue(graph.isAcyclic());
  }

  @Test
  public void testFindCycles() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    graph.addEdge("A", "B");
    graph.addEdge("B", "C");
    graph.addEdge("C", "A");
    graph.addEdge("D", "E");
    graph.addEdge("E", "D");
    graph.addEdge("F", "F");
    assertEquals(
        ImmutableSet.of(
            ImmutableSet.of("A", "B", "C"),
            ImmutableSet.of("D", "E"),
            ImmutableSet.of("F")),
        graph.findCycles());
  }

  @Test
  public void testTrivialDisconnectedGraphIsAcyclic() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<String>();
    graph.addNode("A");
    graph.addNode("B");
    graph.addNode("C");
    assertTrue(graph.isAcyclic());
  }
}
