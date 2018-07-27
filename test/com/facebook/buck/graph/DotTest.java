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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

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
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(Functions.identity())
        .build()
        .writeOutput(output);

    String dotGraph = output.toString();
    List<String> lines =
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    // remove attributes because we are not interested what styles and colors are default
    lines = lines.stream().map(p -> p.replaceAll(" \\[.*\\]", "")).collect(Collectors.toList());

    Set<String> edges = ImmutableSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        edges,
        ImmutableSet.of(
            "  A -> B;",
            "  B -> C;",
            "  B -> D;",
            "  C -> E;",
            "  D -> E;",
            "  A -> E;",
            "  A;",
            "  B;",
            "  C;",
            "  D;",
            "  E;"));

    assertEquals("}", lines.get(lines.size() - 1));
  }

  @Test
  public void testGenerateDotOutputFilter() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    mutableGraph.addEdge("B", "C");
    mutableGraph.addEdge("B", "D");
    mutableGraph.addEdge("C", "E");
    mutableGraph.addEdge("D", "E");
    mutableGraph.addEdge("A", "E");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    ImmutableSet<String> filter =
        ImmutableSet.<String>builder().add("A").add("B").add("C").add("D").build();

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(Functions.identity())
        .setNodesToFilter(filter::contains)
        .build()
        .writeOutput(output);

    String dotGraph = output.toString();
    List<String> lines =
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    // remove attributes because we are not interested what styles and colors are default
    lines = lines.stream().map(p -> p.replaceAll(" \\[.*\\]", "")).collect(Collectors.toList());

    Set<String> edges = ImmutableSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        edges,
        ImmutableSet.of("  A -> B;", "  B -> C;", "  B -> D;", "  A;", "  B;", "  C;", "  D;"));

    assertEquals("}", lines.get(lines.size() - 1));
  }

  @Test
  public void testGenerateDotOutputWithColors() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    StringBuilder output = new StringBuilder();
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .build()
        .writeOutput(output);

    String dotGraph = output.toString();
    List<String> lines =
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    Set<String> edges = ImmutableSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        edges,
        ImmutableSet.of(
            "  A -> B;",
            "  A [style=filled,color=springgreen3];",
            "  B [style=filled,color=indianred1];"));

    assertEquals("}", lines.get(lines.size() - 1));
  }

  @Test
  public void testGenerateDotOutputWithCustomAttributes() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    StringBuilder output = new StringBuilder();
    ImmutableMap<String, ImmutableSortedMap<String, String>> nodeToAttributeProvider =
        ImmutableMap.of("A", ImmutableSortedMap.of("x", "foo", "y", "b.r"));
    Dot.builder(graph, "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .setNodeToAttributes(
            node -> nodeToAttributeProvider.getOrDefault(node, ImmutableSortedMap.of()))
        .build()
        .writeOutput(output);

    String dotGraph = output.toString();
    List<String> lines =
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    assertEquals("digraph the_graph {", lines.get(0));

    Set<String> edges = ImmutableSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        edges,
        ImmutableSet.of(
            "  A -> B;",
            "  A [style=filled,color=springgreen3,buck_x=foo,buck_y=\"b.r\"];",
            "  B [style=filled,color=indianred1];"));

    assertEquals("}", lines.get(lines.size() - 1));
  }

  @Test
  public void testEscaping() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "//B");
    mutableGraph.addEdge("//B", "C1 C2");
    mutableGraph.addEdge("//B", "D\"");
    mutableGraph.addEdge("Z//E", "Z//F");
    mutableGraph.addEdge("A", "A.B");
    mutableGraph.addEdge("A", "A,B");
    mutableGraph.addEdge("A", "[A]");
    mutableGraph.addEdge("A", "");

    StringBuilder output = new StringBuilder();

    Dot.builder(new DirectedAcyclicGraph<>(mutableGraph), "the_graph")
        .setNodeToName(Functions.identity())
        .setNodeToTypeName(name -> name.equals("A") ? "android_library" : "java_library")
        .build()
        .writeOutput(output);

    String dotGraph = output.toString();
    List<String> lines =
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(dotGraph));

    // remove attributes because we are not interested what styles and colors are default
    lines = lines.stream().map(p -> p.replaceAll(" \\[.*\\]", "")).collect(Collectors.toList());

    ImmutableSet<String> edges = ImmutableSortedSet.copyOf(lines.subList(1, lines.size() - 1));
    assertEquals(
        ImmutableSortedSet.of(
            "  \"\";",
            "  \"//B\" -> \"C1 C2\";",
            "  \"//B\" -> \"D\\\"\";",
            "  \"//B\";",
            "  \"A,B\";",
            "  \"A.B\";",
            "  \"C1 C2\";",
            "  \"D\\\"\";",
            "  \"Z//E\" -> \"Z//F\";",
            "  \"Z//E\";",
            "  \"Z//F\";",
            "  \"[A]\";",
            "  A -> \"\";",
            "  A -> \"//B\";",
            "  A -> \"A,B\";",
            "  A -> \"A.B\";",
            "  A -> \"[A]\";",
            "  A;"),
        edges);
  }
}
