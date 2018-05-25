/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.build_slave.DistributableBuildGraph.DistributableNode;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DistributableBuildGraphTest {

  public static DistributableBuildGraph createGraph(
      List<Pair<String, String>> directedEdgeList, Set<String> uncachableNodes) {
    Map<String, Set<String>> forwardDeps = new HashMap<>();
    Map<String, Set<String>> reverseDeps = new HashMap<>();
    Set<String> nodeNames = new HashSet<>();

    for (Pair<String, String> edge : directedEdgeList) {
      forwardDeps.computeIfAbsent(edge.getFirst(), k -> new HashSet<>()).add(edge.getSecond());
      reverseDeps.computeIfAbsent(edge.getSecond(), k -> new HashSet<>()).add(edge.getFirst());
      nodeNames.add(edge.getFirst());
      nodeNames.add(edge.getSecond());
    }

    ImmutableMap.Builder<String, DistributableNode> allNodes = ImmutableMap.builder();
    ImmutableSet.Builder<DistributableNode> leafNodes = ImmutableSet.builder();
    for (String name : nodeNames) {
      ImmutableSet<String> dependencies =
          ImmutableSet.copyOf(forwardDeps.getOrDefault(name, new HashSet<>()));
      ImmutableSet<String> dependents =
          ImmutableSet.copyOf(reverseDeps.getOrDefault(name, new HashSet<>()));
      DistributableNode node =
          new DistributableNode(name, dependents, dependencies, uncachableNodes.contains(name));
      allNodes.put(name, node);
      if (dependencies.size() == 0) {
        leafNodes.add(node);
      }
    }

    return new DistributableBuildGraph(allNodes.build(), leafNodes.build());
  }

  private DistributableBuildGraph graph;

  @Before
  public void setUp() {
    /* Graph:
     *                  a
     *                  |
     *             +----+------+
     *             |           |
     *             b           c(uncachable)
     *             |           |
     *         +---^----v------+----+
     *         |        |      |    |
     *         d        e(uc)  f    g(uc)
     *         |        |      |    |
     *       +-^---+  +-^---v--^-+  h
     *       |     |  |     |    |
     *       i(uc) j  k(uc) l    m
     */
    graph =
        createGraph(
            ImmutableList.of(
                new Pair<>("a", "b"),
                new Pair<>("a", "c"),
                new Pair<>("b", "d"),
                new Pair<>("b", "e"),
                new Pair<>("c", "e"),
                new Pair<>("c", "f"),
                new Pair<>("c", "g"),
                new Pair<>("d", "i"),
                new Pair<>("d", "j"),
                new Pair<>("e", "k"),
                new Pair<>("e", "l"),
                new Pair<>("f", "l"),
                new Pair<>("f", "m"),
                new Pair<>("g", "h")),
            ImmutableSet.of("c", "e", "g", "i", "k"));
  }

  @Test
  public void testTransitiveCachableDeps() {
    // Check for all nodes, but randomize the order to make sure that doesn't affect computation.
    Assert.assertEquals(
        graph.getNode("a").getTransitiveCacheableDependents(graph), ImmutableSet.of());
    Assert.assertEquals(
        graph.getNode("f").getTransitiveCacheableDependents(graph), ImmutableSet.of("a"));
    Assert.assertEquals(
        graph.getNode("b").getTransitiveCacheableDependents(graph), ImmutableSet.of("a"));
    Assert.assertEquals(
        graph.getNode("c").getTransitiveCacheableDependents(graph), ImmutableSet.of("a"));
    Assert.assertEquals(
        graph.getNode("d").getTransitiveCacheableDependents(graph), ImmutableSet.of("b"));
    Assert.assertEquals(
        graph.getNode("l").getTransitiveCacheableDependents(graph), ImmutableSet.of("f", "b", "a"));
    Assert.assertEquals(
        graph.getNode("k").getTransitiveCacheableDependents(graph), ImmutableSet.of("b", "a"));
    Assert.assertEquals(
        graph.getNode("e").getTransitiveCacheableDependents(graph), ImmutableSet.of("b", "a"));
    Assert.assertEquals(
        graph.getNode("h").getTransitiveCacheableDependents(graph), ImmutableSet.of("a"));
    Assert.assertEquals(
        graph.getNode("j").getTransitiveCacheableDependents(graph), ImmutableSet.of("d"));
    Assert.assertEquals(
        graph.getNode("i").getTransitiveCacheableDependents(graph), ImmutableSet.of("d"));
    Assert.assertEquals(
        graph.getNode("m").getTransitiveCacheableDependents(graph), ImmutableSet.of("f"));
    Assert.assertEquals(
        graph.getNode("g").getTransitiveCacheableDependents(graph), ImmutableSet.of("a"));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private static void assertThrows(Runnable f) {
    try {
      f.run();
      Assert.fail("Expected to throw.");
    } catch (Exception e) {
      // Expected.
    }
  }

  @Test
  public void testFinishDeps() {
    // Dep to non-existent node.
    assertThrows(() -> graph.getNode("a").finishDependency("z"));

    // Non-existent dep to valid node.
    assertThrows(() -> graph.getNode("f").finishDependency("a"));

    // Non-direct dep via cachable.
    assertThrows(() -> graph.getNode("b").finishDependency("j"));

    // Non-direct dep via uncachable.
    assertThrows(() -> graph.getNode("b").finishDependency("l"));

    // Valid cachable and uncachable deps.
    Assert.assertEquals(2, graph.getNode("e").getNumUnsatisfiedDependencies());
    graph.getNode("e").finishDependency("k");
    Assert.assertEquals(1, graph.getNode("e").getNumUnsatisfiedDependencies());
    // Double finish.
    assertThrows(() -> graph.getNode("e").finishDependency("k"));

    graph.getNode("e").finishDependency("l");
    Assert.assertEquals(0, graph.getNode("e").getNumUnsatisfiedDependencies());
    // Double finish.
    assertThrows(() -> graph.getNode("e").finishDependency("l"));

    // Make sure that node l was not marked complete for all dependents.
    graph.getNode("f").finishDependency("l");
  }
}
