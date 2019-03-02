/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.facebook.buck.cli.ThriftOutputUtils.edgesToStringList;
import static com.facebook.buck.cli.ThriftOutputUtils.nodesToStringList;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.query.thrift.DirectedAcyclicGraphNode;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.junit.Test;

public class ThriftOutputTest {

  @Test
  public void testGenerateThriftOutput() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    mutableGraph.addEdge("B", "C");
    mutableGraph.addEdge("B", "D");
    mutableGraph.addEdge("C", "E");
    mutableGraph.addEdge("D", "E");
    mutableGraph.addEdge("A", "E");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    byte[] byteArray;
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

      ThriftOutput.builder(graph)
          .nodeToNameMappingFunction(Functions.identity())
          .build()
          .writeOutput(new PrintStream(byteArrayOutputStream));
      byteArray = byteArrayOutputStream.toByteArray();
    }

    com.facebook.buck.query.thrift.DirectedAcyclicGraph thriftDag =
        new com.facebook.buck.query.thrift.DirectedAcyclicGraph();

    ThriftUtil.deserialize(ThriftProtocol.BINARY, byteArray, thriftDag);
    assertEquals(5, thriftDag.getNodesSize());
    assertThat(
        nodesToStringList(thriftDag.getNodes()), containsInAnyOrder("A", "B", "C", "D", "E"));

    assertEquals(6, thriftDag.getEdgesSize());
    assertThat(
        edgesToStringList(thriftDag.getEdges()),
        containsInAnyOrder("A->B", "B->C", "B->D", "C->E", "D->E", "A->E"));
  }

  @Test
  public void testGenerateThriftOutputWithFilterPredicate() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    mutableGraph.addEdge("B", "C");
    mutableGraph.addEdge("B", "D");
    mutableGraph.addEdge("C", "E");
    mutableGraph.addEdge("D", "E");
    mutableGraph.addEdge("A", "E");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    Set<String> filterSet = new HashSet<>(Arrays.asList("A", "B", "C"));

    byte[] byteArray;
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

      ThriftOutput.builder(graph)
          .filter(filterSet::contains)
          .nodeToNameMappingFunction(Functions.identity())
          .build()
          .writeOutput(new PrintStream(byteArrayOutputStream));
      byteArray = byteArrayOutputStream.toByteArray();
    }

    com.facebook.buck.query.thrift.DirectedAcyclicGraph thriftDag =
        new com.facebook.buck.query.thrift.DirectedAcyclicGraph();

    ThriftUtil.deserialize(ThriftProtocol.BINARY, byteArray, thriftDag);

    assertEquals(3, thriftDag.getNodesSize());
    assertThat(nodesToStringList(thriftDag.getNodes()), containsInAnyOrder("A", "B", "C"));

    assertEquals(2, thriftDag.getEdgesSize());
    assertThat(edgesToStringList(thriftDag.getEdges()), containsInAnyOrder("A->B", "B->C"));
  }

  @Test
  public void testGenerateThriftOutputWithCustomAttributes() throws IOException {
    MutableDirectedGraph<String> mutableGraph = new MutableDirectedGraph<>();
    mutableGraph.addEdge("A", "B");
    DirectedAcyclicGraph<String> graph = new DirectedAcyclicGraph<>(mutableGraph);

    ImmutableMap<String, ImmutableSortedMap<String, String>> nodeToAttributeProvider =
        ImmutableMap.of("A", ImmutableSortedMap.of("x", "foo", "y", "b.r"));

    byte[] byteArray;
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

      ThriftOutput.builder(graph)
          .nodeToAttributesFunction(
              node -> nodeToAttributeProvider.getOrDefault(node, ImmutableSortedMap.of()))
          .nodeToNameMappingFunction(Functions.identity())
          .build()
          .writeOutput(new PrintStream(byteArrayOutputStream));
      byteArray = byteArrayOutputStream.toByteArray();
    }

    com.facebook.buck.query.thrift.DirectedAcyclicGraph thriftDag =
        new com.facebook.buck.query.thrift.DirectedAcyclicGraph();

    ThriftUtil.deserialize(ThriftProtocol.BINARY, byteArray, thriftDag);

    assertEquals(2, thriftDag.getNodesSize());
    List<DirectedAcyclicGraphNode> nodes = thriftDag.getNodes();
    assertThat(nodesToStringList(nodes), containsInAnyOrder("A", "B"));
    DirectedAcyclicGraphNode nodeA = getNodeByName(nodes, "A");
    assertTrue(nodeA.isSetNodeAttributes());
    Map<String, String> nodeAAttributes = nodeA.getNodeAttributes();
    assertEquals(2, nodeAAttributes.size());
    assertEquals(nodeAAttributes.get("x"), "foo");
    assertEquals(nodeAAttributes.get("y"), "b.r");

    DirectedAcyclicGraphNode nodeB = getNodeByName(nodes, "B");
    assertFalse(nodeB.isSetNodeAttributes());

    assertEquals(1, thriftDag.getEdgesSize());
    assertThat(edgesToStringList(thriftDag.getEdges()), containsInAnyOrder("A->B"));
  }

  @Nonnull
  private DirectedAcyclicGraphNode getNodeByName(
      List<DirectedAcyclicGraphNode> nodes, String nodeName) {
    return nodes.stream().filter(node -> node.getName().equals(nodeName)).findFirst().get();
  }
}
