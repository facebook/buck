/*
 * Copyright 2016-present Facebook, Inc.
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
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

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
  private DirectedAcyclicGraph<String> makeComplexGraph() {
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

  @Test
  public void sorts() {
    DirectedAcyclicGraph<String> graph = makeComplexGraph();
    ImmutableList<String> sorted = TopologicalSort.sort(graph);
    assertEquals(graph.getNodes(), ImmutableSet.copyOf(sorted));
    assertOrdering(sorted, "B", "A");
    assertOrdering(sorted, "C", "A");
    assertOrdering(sorted, "D", "B");
    assertOrdering(sorted, "E", "C");
    assertOrdering(sorted, "F", "D");
    assertOrdering(sorted, "G", "C");
    assertOrdering(sorted, "G", "D");
  }

  @Test
  public void visitsChildrenInSortedOrder() {
    //    A
    //  /  \
    // B    C
    assertEquals(
        ImmutableList.of("B", "C", "A"),
        TopologicalSort.sort(makeOrderedGraph("A", "B", "C")));

    //    B
    //  /  \
    // A    C
    assertEquals(
        ImmutableList.of("A", "C", "B"),
        TopologicalSort.sort(makeOrderedGraph("B", "A", "C")));

    //    A
    //  /  \
    // C    B
    assertEquals(
        ImmutableList.of("B", "C", "A"),
        TopologicalSort.sort(makeOrderedGraph("A", "C", "B")));

    //    B
    //  /  \
    // C    A
    assertEquals(
        ImmutableList.of("A", "C", "B"),
        TopologicalSort.sort(makeOrderedGraph("B", "C", "A")));

    //    C
    //  /  \
    // A    B
    assertEquals(
        ImmutableList.of("A", "B", "C"),
        TopologicalSort.sort(makeOrderedGraph("C", "A", "B")));

    //    C
    //  /  \
    // B    A
    assertEquals(
        ImmutableList.of("A", "B", "C"),
        TopologicalSort.sort(makeOrderedGraph("C", "B", "A")));
  }

  @Test
  public void visitsRootsInSortedOrder() {
    //   A     D
    //  / \
    // B   C
    assertEquals(
        ImmutableList.of("B", "C", "A", "D"),
        TopologicalSort.sort(makeOrderedGraph("A", "B", "C", Optional.of("D")))
    );
    //   D     A
    //  / \
    // B   C
    assertEquals(
        ImmutableList.of("A", "B", "C", "D"),
        TopologicalSort.sort(makeOrderedGraph("D", "B", "C", Optional.of("A")))
    );
  }

  private TraversableGraph<String> makeOrderedGraph(String parent, String left, String right) {
    return makeOrderedGraph(parent, left, right, Optional.empty());
  }

  private TraversableGraph<String> makeOrderedGraph(
      String parent, String left, String right, Optional<String> isolatedNode) {
    Iterable<String> isolatedNodeIterable = isolatedNode.isPresent()
        ? ImmutableList.of(isolatedNode.get()) : ImmutableList.of();

    return new TraversableGraph<String>() {
      @Override
      public Iterable<String> getNodesWithNoIncomingEdges() {
        return Iterables.concat(ImmutableList.of(parent), isolatedNodeIterable);
      }

      @Override
      public Iterable<String> getNodesWithNoOutgoingEdges() {
        return Iterables.concat(ImmutableList.of(left, right), isolatedNodeIterable);
      }

      @Override
      public Iterable<String> getIncomingNodesFor(String sink) {
        if (isolatedNode.isPresent() && sink.equals(isolatedNode.get())) {
          return ImmutableList.of();
        }
        return sink.equals(parent) ? ImmutableList.of() : ImmutableList.of(parent);
      }

      @Override
      public Iterable<String> getOutgoingNodesFor(String source) {
        if (isolatedNode.isPresent() && source.equals(isolatedNode.get())) {
          return ImmutableList.of();
        }
        return source.equals(parent) ? ImmutableList.of(left, right) : ImmutableList.of();
      }

      @Override
      public Iterable<String> getNodes() {
        return Iterables.concat(ImmutableList.of(parent, left, right), isolatedNodeIterable);
      }
    };
  }

  private <T> void assertOrdering(List<T> list, T before, T after) {
    assertTrue(
        String.format("Expected %s to be before %s in %s", before, after, list),
        list.indexOf(before) < list.indexOf(after));
  }
}
