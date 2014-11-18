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

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;

public class AbstractBreadthFirstTraversalTest {

  @Test
  public void testIsBreadthFirst() {
    // The dependency graph is built as follows:
    //
    //                       J
    //                /    /   \     \
    //              F    G       H     I
    //            /   \        /   \     \
    //          E       D    C       B     A
    //
    FakeNode nodeA = createNode("A");
    FakeNode nodeB = createNode("B");
    FakeNode nodeC = createNode("C");
    FakeNode nodeD = createNode("D");
    FakeNode nodeE = createNode("E");

    FakeNode nodeF = createNode("F", nodeE, nodeD);
    FakeNode nodeG = createNode("G");
    FakeNode nodeH = createNode("H", nodeC, nodeB);
    FakeNode nodeI = createNode("I", nodeA);

    FakeNode initialNode = createNode("J", nodeF, nodeG, nodeH, nodeI);

    final List<FakeNode> nodeTraversalOrder = Lists.newArrayList();
    new AbstractBreadthFirstTraversal<FakeNode>(initialNode) {
      @Override
      public ImmutableSet<FakeNode> visit(FakeNode node) {
        nodeTraversalOrder.add(node);
        return node.getDeps();
      }
    }.start();

    assertEquals(
        "Dependencies should be explored breadth-first, using lexicographic ordering to break ties",
        ImmutableList.of(initialNode,
            nodeF,
            nodeG,
            nodeH,
            nodeI,
            nodeD,
            nodeE,
            nodeB,
            nodeC,
            nodeA),
        nodeTraversalOrder);
  }

  @Test
  public void testSubsetWorks() {
    // The dependency graph is built as follows:
    //
    //                       10
    //                /    /   \     \
    //              6    7       8     9
    //            /   \        /   \     \
    //          5       4    3       2     1
    //
    FakeNode node1 = createNode("1");
    FakeNode node2 = createNode("2");
    FakeNode node3 = createNode("3");
    FakeNode node4 = createNode("4");
    FakeNode node5 = createNode("5");

    FakeNode node6 = createNode("6", node5, node4);
    FakeNode node7 = createNode("7");
    FakeNode node8 = createNode("8", node3, node2);
    FakeNode node9 = createNode("9", node1);

    FakeNode initialNode = createNode("10", node6, node7, node8, node9);

    final List<FakeNode> nodeTraversalOrder = Lists.newArrayList();

    // This visitor only visits dependencies whose node names are even numbers.
    new AbstractBreadthFirstTraversal<FakeNode>(initialNode) {
      @Override
      public ImmutableSet<FakeNode> visit(FakeNode node) {
        nodeTraversalOrder.add(node);
        return ImmutableSet.copyOf(Iterables.filter(node.getDeps(), new Predicate<FakeNode>() {
          @Override
          public boolean apply(FakeNode input) {
            return Integer.parseInt(input.getName()) % 2 == 0;
          }
        }));
      }
    }.start();

    assertEquals(
        "Dependencies should be explored breadth-first, only containing nodes whose node name is " +
            "an even number",
        ImmutableList.of(initialNode,
            node6,
            node8,
            node4,
            node2),
        nodeTraversalOrder);
  }

  private static class FakeNode implements Comparable<FakeNode> {
    protected String name;
    protected ImmutableSortedSet<FakeNode> deps;

    public FakeNode(String name, ImmutableSortedSet<FakeNode> deps) {
      this.name = name;
      this.deps = deps;
    }

    public String getName() {
      return name;
    }

    public ImmutableSet<FakeNode> getDeps() {
      return deps;
    }

    @Override
    public int compareTo(FakeNode other) {
      return this.name.compareTo(other.name);
    }
  }

  private FakeNode createNode(String name, FakeNode... deps) {
    return new FakeNode(name, ImmutableSortedSet.copyOf(deps));
  }
}
