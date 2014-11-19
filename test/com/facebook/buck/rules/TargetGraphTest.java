/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

public class TargetGraphTest {

  private TargetNode<?> nodeA;
  private TargetNode<?> nodeB;
  private TargetNode<?> nodeC;
  private TargetNode<?> nodeD;
  private TargetNode<?> nodeE;
  private TargetNode<?> nodeF;
  private TargetNode<?> nodeG;
  private TargetNode<?> nodeH;
  private TargetNode<?> nodeI;
  private TargetGraph targetGraph;

  @Before
  public void setUp() {
    // Creates the following target graph:
    //      A   B
    //     /|\ /
    //    C D E
    //    |/| |\
    //    F G | H
    //       \|
    //        I

    nodeI = createTargetNode("I");
    nodeH = createTargetNode("H");
    nodeG = createTargetNode("G", nodeI);
    nodeF = createTargetNode("F");
    nodeE = createTargetNode("E", nodeH, nodeI);
    nodeD = createTargetNode("D", nodeF, nodeG);
    nodeC = createTargetNode("C", nodeF);
    nodeB = createTargetNode("B", nodeE);
    nodeA = createTargetNode("A", nodeC, nodeD, nodeE);
    targetGraph = TargetGraphFactory.newInstance(
        nodeA,
        nodeB,
        nodeC,
        nodeD,
        nodeE,
        nodeF,
        nodeG,
        nodeH,
        nodeI);
  }

  @Test
  public void testEmptySubgraph() {
    ImmutableSet<TargetNode<?>> roots = ImmutableSet.of();
    ImmutableSet<TargetNode<?>> expectedNodes = ImmutableSet.of();
    checkSubgraph(roots, expectedNodes);
  }

  @Test
  public void testCompleteSubgraph() {
    ImmutableSet<TargetNode<?>> roots = ImmutableSet.of(nodeA, nodeB);
    ImmutableSet<TargetNode<?>> expectedNodes = targetGraph.getNodes();
    checkSubgraph(roots, expectedNodes);
  }

  @Test
  public void testSubgraphWithAllRoots() {
    ImmutableSet<TargetNode<?>> roots = targetGraph.getNodes();
    ImmutableSet<TargetNode<?>> expectedNodes = targetGraph.getNodes();
    checkSubgraph(roots, expectedNodes);
  }

  @Test
  public void testSubgraphWithoutEdges() {
    ImmutableSet<TargetNode<?>> roots = ImmutableSet.of(nodeF, nodeH, nodeI);
    ImmutableSet<TargetNode<?>> expectedNodes = ImmutableSet.of(nodeF, nodeH, nodeI);
    checkSubgraph(roots, expectedNodes);
  }

  @Test
  public void testPartialSubgraph1() {
    ImmutableSet<TargetNode<?>> roots = ImmutableSet.of(nodeB, nodeD, nodeH);
    ImmutableSet<TargetNode<?>> expectedNodes = ImmutableSet.<TargetNode<?>>of(
        nodeB, nodeD, nodeE, nodeF, nodeG, nodeH, nodeI);
    checkSubgraph(roots, expectedNodes);
  }

  @Test
  public void testPartialSubgraph2() {
    ImmutableSet<TargetNode<?>> roots = ImmutableSet.<TargetNode<?>>of(nodeD);
    ImmutableSet<TargetNode<?>> expectedNodes = ImmutableSet.of(nodeD, nodeF, nodeG, nodeI);
    checkSubgraph(roots, expectedNodes);
  }

  private void checkSubgraph(
      ImmutableSet<TargetNode<?>> roots,
      ImmutableSet<TargetNode<?>> expectedNodes) {
    TargetGraph subgraph = targetGraph.getSubgraph(roots);
    assertEquals("Subgraph should contain the roots and their dependencies",
        expectedNodes, subgraph.getNodes());

    for (TargetNode<?> node : subgraph.getNodes()) {
      assertEquals("Subgraph should have the same edges as the original graph",
          targetGraph.getOutgoingNodesFor(node), subgraph.getOutgoingNodesFor(node));
    }

    for (TargetNode<?> node : subgraph.getNodes()) {
      assertEquals("subgraph.get should return a node containing the specified BuildTarget",
          node, subgraph.get(node.getBuildTarget()));
    }
  }

  private TargetNode<?> createTargetNode(String name, TargetNode<?>... deps) {
    BuildTarget buildTarget = BuildTarget.builder("//foo", name).build();
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget);
    for (TargetNode<?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    return targetNodeBuilder.build();
  }

}
