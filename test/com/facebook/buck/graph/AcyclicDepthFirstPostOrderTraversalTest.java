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

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Unit test for {@link AcyclicDepthFirstPostOrderTraversal}. */
public class AcyclicDepthFirstPostOrderTraversalTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  /**
   * Verifies that a traversal of a well-formed DAG proceeds as expected.
   *
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
  public void testExpectedTraversal() throws CycleException {
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
    assertEquals(expectedExploredNodes.size(), dfs.numFindChildrenCalls);
  }

  /**
   * Creates the following graph and verifies that the cycle is found.
   *
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   *
   * Note that there is a circular dependency from F -> C -> E -> F.
   */
  @Test
  public void testCycleDetection() {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("A", "C");
    graph.put("B", "D");
    graph.put("B", "E");
    graph.put("C", "E");
    graph.put("D", "F");
    graph.put("E", "F");
    graph.put("F", "C");
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of("A"));
    } catch (CycleException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText(
                  "The following circular dependency has been found:", "F -> C -> E -> F")));
      assertEquals(ImmutableList.of("F", "C", "E", "F"), e.getCycle());
    }
  }

  /** Ensures that a cycle is detected in a trivial graph of a single node that points to itself. */
  @Test
  public void testTrivialCycle() {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "A");
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of("A"));
    } catch (CycleException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText("The following circular dependency has been found:", "A -> A")));
      assertEquals(ImmutableList.of("A", "A"), e.getCycle());
    }
  }

  /**
   * Verifies that the reported cycle mentions only A, B, and D, but not C or E.
   *
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
  public void testCycleExceptionDoesNotContainUnrelatedNodes() {
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
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText(
                  "The following circular dependency has been found:", "A -> B -> D -> A")));
      assertEquals(ImmutableList.of("A", "B", "D", "A"), e.getCycle());
    }
  }

  /**
   * Verifies that traverse() handles multiple initial nodes correctly.
   *
   * <pre>
   *         A
   *       /
   *     B
   *   / | \
   * C   D   E
   * </pre>
   */
  @Test
  public void testTraverseMultipleInitialNodes() throws CycleException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("B", "C");
    graph.put("B", "D");
    graph.put("B", "E");

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);
    dfs.traverse(ImmutableList.of("A", "B", "C", "D", "E"));
    assertEquals(ImmutableList.of("C", "D", "E", "B", "A"), dfs.exploredNodes);
  }

  @Test
  public void testShortCircuitTraversal() throws CycleException {
    Multimap<String, String> graph = LinkedListMultimap.create();
    graph.put("A", "B");
    graph.put("B", "C");
    graph.put("C", "D");
    graph.put("B", "E");

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph, node -> !node.equals("C"));
    dfs.traverse(ImmutableList.of("A"));
    assertEquals(ImmutableList.of("C", "E", "B", "A"), dfs.exploredNodes);
  }

  private static class TestDagDepthFirstSearch {

    private final Multimap<String, String> graph;
    private final Predicate<String> shouldExploreChildren;
    private final List<String> exploredNodes = new ArrayList<>();
    private int numFindChildrenCalls;

    public TestDagDepthFirstSearch(Multimap<String, String> graph) {
      this(graph, node -> true);
    }

    public TestDagDepthFirstSearch(
        Multimap<String, String> graph, Predicate<String> shouldExploreChildren) {
      this.graph = graph;
      this.shouldExploreChildren = shouldExploreChildren;
      this.numFindChildrenCalls = 0;
    }

    public void traverse(Iterable<String> initial)
        throws AcyclicDepthFirstPostOrderTraversal.CycleException {
      AcyclicDepthFirstPostOrderTraversal<String> traversal =
          new AcyclicDepthFirstPostOrderTraversal<>(
              node -> {
                ++numFindChildrenCalls;
                return graph.get(node).iterator();
              });
      for (String node : traversal.traverse(initial, shouldExploreChildren)) {
        exploredNodes.add(node);
      }
    }
  }
}
