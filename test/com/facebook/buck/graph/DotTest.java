/*
 * Copyright 2013-present Facebook, Inc.
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

import com.google.common.base.Functions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class DotTest {

  @Test
  public void testGenerateDotOutput() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    mutableGraph.addEdge("B", "C");
    mutableGraph.addEdge("B", "D");
    mutableGraph.addEdge("C", "E");
    mutableGraph.addEdge("D", "E");
    mutableGraph.addEdge("A", "E");
    DirectedAcyclicGraph<String> graph = new DefaultDirectedAcyclicGraph<>(
        mutableGraph);

    StringBuilder output = new StringBuilder();
    Dot<String> dot = new Dot<String>(graph, "the_graph", Functions.<String>identity(), output);
    dot.writeOutput();

    String dotGraph = output.toString();
    List<String> lines = ImmutableList.copyOf(Splitter.on('\n').omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    Set<String> edges = ImmutableSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        edges,
        ImmutableSet.of(
            "  A -> B;",
            "  B -> C;",
            "  B -> D;",
            "  C -> E;",
            "  D -> E;",
            "  A -> E;"));

    assertEquals("}", lines.get(lines.size() - 1));
  }
}
