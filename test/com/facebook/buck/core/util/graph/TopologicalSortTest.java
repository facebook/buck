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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import java.util.List;
import org.junit.Test;

public class TopologicalSortTest {

  //           A
  //         /  \
  //       B     C
  //      /     / \
  //    D    __/   E
  //  /  \  /
  // F    G
  //
  // Nodes and edges are added in weird orders to avoid default insertion orders happening to be
  // sorted.
  private DirectedAcyclicGraph<String> makeGraph() {
    MutableDirectedGraph<String> graph = new MutableDirectedGraph<>();
    graph.addNode("C");
    graph.addNode("B");
    graph.addNode("E");
    graph.addNode("A");
    graph.addNode("F");
    graph.addNode("G");
    graph.addNode("D");

    graph.addEdge("D", "G");
    graph.addEdge("A", "C");
    graph.addEdge("D", "F");
    graph.addEdge("B", "D");
    graph.addEdge("A", "B");
    graph.addEdge("C", "E");
    graph.addEdge("C", "G");
    return new DirectedAcyclicGraph<>(graph);
  }

  public void assertTopologicallySorted(ImmutableList<? extends String> sorted) {
    assertOrdering(sorted, "B", "A");
    assertOrdering(sorted, "C", "A");
    assertOrdering(sorted, "D", "B");
    assertOrdering(sorted, "E", "C");
    assertOrdering(sorted, "F", "D");
    assertOrdering(sorted, "G", "C");
    assertOrdering(sorted, "G", "D");
  }

  @Test
  public void sorts() {
    DirectedAcyclicGraph<String> graph = makeGraph();
    ImmutableList<String> sorted = TopologicalSort.sort(graph);
    assertEquals(graph.getNodes(), ImmutableSet.copyOf(sorted));
    assertTopologicallySorted(sorted);
  }

  @Test
  public void sortsSnowflakes() {
    DirectedAcyclicGraph<String> graph = makeGraph();
    ImmutableList<? extends String> sorted =
        TopologicalSort.snowflakeSort(
            graph.getNodesWithNoIncomingEdges(),
            s -> graph.getOutgoingNodesFor(s).iterator(),
            Ordering.natural());

    assertEquals(graph.getNodes(), ImmutableSet.copyOf(sorted));
    assertTopologicallySorted(sorted);

    // snowflakeSort also guarantees that each "level" of leaves is sorted.
    assertOrdering(sorted, "E", "F");
    assertOrdering(sorted, "F", "G");
    assertOrdering(sorted, "C", "D");
  }

  @Test
  public void sortsTraversable() {
    DirectedAcyclicGraph<String> graph = makeGraph();
    ImmutableList<? extends String> sorted =
        TopologicalSort.sort(
            graph.getNodesWithNoIncomingEdges(), s -> graph.getOutgoingNodesFor(s).iterator());
    assertTopologicallySorted(sorted);
  }

  private <T> void assertOrdering(List<? extends T> list, T before, T after) {
    assertTrue(
        String.format("Expected %s to be before %s in %s", before, after, list),
        list.indexOf(before) < list.indexOf(after));
  }
}
