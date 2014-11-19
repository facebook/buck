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

import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import org.junit.Test;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Unit test for {@link AbstractAcyclicDepthFirstPostOrderTraversal}.
 */
public class AbstractAcyclicDepthFirstPostOrderTraversalTest {

  /**
   * Verifies that a traversal of a well-formed DAG proceeds as expected.
   * <pre>
   *         A
   *       /   \
   *     B       C
   *   /   \   /
   * D       E
   *   \   /
   *     F
   * </pre>
   */
  @Test
  public void testExpectedTraversal() throws CycleException, IOException, InterruptedException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("A", "C");
    graph.put("B", "D");
    graph.put("B", "E");
    graph.put("C", "E");
    graph.put("D", "F");
    graph.put("E", "F");
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    dfs.traverse(ImmutableList.of("A"));
    ImmutableList<String> expectedExploredNodes = ImmutableList.of("F", "D", "E", "B", "C", "A");
    assertEquals(expectedExploredNodes, dfs.exploredNodes);
    assertEquals(expectedExploredNodes, dfs.paramToNodesInExplorationOrder);
    assertEquals(expectedExploredNodes.size(), dfs.numFindChildrenCalls);
  }

  /**
   * Creates the following graph and verifies that the cycle is found.
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   * Note that there is a circular dependency from F -> C -> E -> F.
   */
  @Test
  public void testCycleDetection() throws IOException, InterruptedException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("A", "C");
    graph.put("B", "D");
    graph.put("B", "E");
    graph.put("C", "E");
    graph.put("D", "F");
    graph.put("E", "F");
    graph.put("F", "C");
    AbstractAcyclicDepthFirstPostOrderTraversal<String> dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of("A"));
    } catch (CycleException e) {
      assertEquals("Cycle found: F -> C -> E -> F", e.getMessage());
      assertEquals(ImmutableList.of("F", "C", "E", "F"), e.getCycle());
    }
  }

  /**
   * Ensures that a cycle is detected in a trivial graph of a single node that points to itself.
   */
  @Test
  public void testTrivialCycle() throws IOException, InterruptedException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "A");
    AbstractAcyclicDepthFirstPostOrderTraversal<String> dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of("A"));
    } catch (CycleException e) {
      assertEquals("Cycle found: A -> A", e.getMessage());
      assertEquals(ImmutableList.of("A", "A"), e.getCycle());
    }
  }

  /**
   * Verifies that the reported cycle mentions only A, B, and D, but not C or E.
   * <pre>
   *         A <---|
   *       /       |
   *     B         |
   *   / | \       |
   * C   D   E     |
   *     |         |
   *     ==========|
   * </pre>
   */
  @Test
  public void testCycleExceptionDoesNotContainUnrelatedNodes()
      throws IOException, InterruptedException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("B", "C");
    graph.put("B", "D");
    graph.put("B", "E");
    graph.put("D", "A");

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);
    try {
      dfs.traverse(ImmutableList.of("A"));
    } catch (CycleException e) {
      assertEquals("Cycle found: A -> B -> D -> A", e.getMessage());
      assertEquals(ImmutableList.of("A", "B", "D", "A"), e.getCycle());
    }
  }

  /**
   * Verifies that traverse() handles multiple initial nodes correctly.
   * <pre>
   *         A
   *       /
   *     B
   *   / | \
   * C   D   E
   * </pre>
   * @throws CycleException
   */
  @Test
  public void testTraverseMultipleInitialNodes()
      throws CycleException, IOException, InterruptedException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("B", "C");
    graph.put("B", "D");
    graph.put("B", "E");

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);
    dfs.traverse(ImmutableList.of("A", "B", "C", "D", "E"));
    assertEquals(ImmutableList.of("C", "D", "E", "B", "A"), dfs.exploredNodes);
  }

  private static class TestDagDepthFirstSearch extends
      AbstractAcyclicDepthFirstPostOrderTraversal<String> {

    private final Multimap<String, String> graph;
    private final List<String> exploredNodes = Lists.newArrayList();
    @Nullable
    private ImmutableList<String> paramToNodesInExplorationOrder;
    private int numFindChildrenCalls;

    private TestDagDepthFirstSearch(Multimap<String, String> graph) {
      this.graph = graph;
      this.numFindChildrenCalls = 0;
    }

    @Override
    protected Iterator<String> findChildren(String node) {
      ++numFindChildrenCalls;
      return graph.get(node).iterator();
    }

    @Override
    protected void onNodeExplored(String node) {
      exploredNodes.add(node);
    }

    @Override
    protected void onTraversalComplete(Iterable<String> nodesInExplorationOrder) {
      paramToNodesInExplorationOrder = ImmutableList.copyOf(nodesInExplorationOrder);
    }
  }
}
