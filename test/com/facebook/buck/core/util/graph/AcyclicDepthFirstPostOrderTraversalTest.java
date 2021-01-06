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

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.DependencyStack;
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

  private enum Node implements DependencyStack.Element {
    A,
    B,
    C,
    D,
    E,
    F,
  }

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
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.A, Node.C);
    graph.put(Node.B, Node.D);
    graph.put(Node.B, Node.E);
    graph.put(Node.C, Node.E);
    graph.put(Node.D, Node.F);
    graph.put(Node.E, Node.F);
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    dfs.traverse(ImmutableList.of(Node.A));
    ImmutableList<Node> expectedExploredNodes =
        ImmutableList.of(Node.F, Node.D, Node.E, Node.B, Node.C, Node.A);
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
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.A, Node.C);
    graph.put(Node.B, Node.D);
    graph.put(Node.B, Node.E);
    graph.put(Node.C, Node.E);
    graph.put(Node.D, Node.F);
    graph.put(Node.E, Node.F);
    graph.put(Node.F, Node.C);
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of(Node.A));
    } catch (CycleException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText(
                  "The following circular dependency has been found:", "F -> C -> E -> F")));
      assertEquals(ImmutableList.of(Node.F, Node.C, Node.E, Node.F), e.getCycle());
    }
  }

  /** Ensures that a cycle is detected in a trivial graph of a single node that points to itself. */
  @Test
  public void testTrivialCycle() {
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.A);
    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);

    try {
      dfs.traverse(ImmutableList.of(Node.A));
    } catch (CycleException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText("The following circular dependency has been found:", "A -> A")));
      assertEquals(ImmutableList.of(Node.A, Node.A), e.getCycle());
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
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.B, Node.C);
    graph.put(Node.B, Node.D);
    graph.put(Node.B, Node.E);
    graph.put(Node.D, Node.A);

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);
    try {
      dfs.traverse(ImmutableList.of(Node.A));
    } catch (CycleException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              linesToText(
                  "The following circular dependency has been found:", "A -> B -> D -> A")));
      assertEquals(ImmutableList.of(Node.A, Node.B, Node.D, Node.A), e.getCycle());
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
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.B, Node.C);
    graph.put(Node.B, Node.D);
    graph.put(Node.B, Node.E);

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph);
    dfs.traverse(ImmutableList.of(Node.A, Node.B, Node.C, Node.D, Node.E));
    assertEquals(ImmutableList.of(Node.C, Node.D, Node.E, Node.B, Node.A), dfs.exploredNodes);
  }

  @Test
  public void testShortCircuitTraversal() throws CycleException {
    Multimap<Node, Node> graph = LinkedListMultimap.create();
    graph.put(Node.A, Node.B);
    graph.put(Node.B, Node.C);
    graph.put(Node.C, Node.D);
    graph.put(Node.B, Node.E);

    TestDagDepthFirstSearch dfs = new TestDagDepthFirstSearch(graph, node -> !node.equals(Node.C));
    dfs.traverse(ImmutableList.of(Node.A));
    assertEquals(ImmutableList.of(Node.C, Node.E, Node.B, Node.A), dfs.exploredNodes);
  }

  private static class TestDagDepthFirstSearch {

    private final Multimap<Node, Node> graph;
    private final Predicate<Node> shouldExploreChildren;
    private final List<Node> exploredNodes = new ArrayList<>();
    private int numFindChildrenCalls;

    public TestDagDepthFirstSearch(Multimap<Node, Node> graph) {
      this(graph, node -> true);
    }

    public TestDagDepthFirstSearch(
        Multimap<Node, Node> graph, Predicate<Node> shouldExploreChildren) {
      this.graph = graph;
      this.shouldExploreChildren = shouldExploreChildren;
      this.numFindChildrenCalls = 0;
    }

    public void traverse(Iterable<Node> initial) throws CycleException {
      AcyclicDepthFirstPostOrderTraversal<Node> traversal =
          new AcyclicDepthFirstPostOrderTraversal<>(
              node -> {
                ++numFindChildrenCalls;
                return graph.get(node).iterator();
              });
      for (Node node : traversal.traverse(initial, shouldExploreChildren)) {
        exploredNodes.add(node);
      }
    }
  }
}
