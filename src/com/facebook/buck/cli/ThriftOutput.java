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

import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.query.thrift.DirectedAcyclicGraphEdge;
import com.facebook.buck.query.thrift.DirectedAcyclicGraphNode;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Class responsible for serialization of DirectedAcyclicGraph into Thrift output format */
public class ThriftOutput<T> {

  private final DirectedAcyclicGraph<T> graph;
  private final Predicate<T> filterPredicate;
  private Function<T, String> nodeToNameMappingFunction;
  private final Function<T, ImmutableSortedMap<String, String>> nodeToAttributesFunction;

  public static <T> ThriftOutput.Builder<T> builder(DirectedAcyclicGraph<T> graph) {
    return new ThriftOutput.Builder<>(graph);
  }

  /**
   * Builder class for Thrift output
   *
   * @param <T>
   */
  public static class Builder<T> {

    private final DirectedAcyclicGraph<T> graph;
    private Predicate<T> filterPredicate;
    private Function<T, String> nodeToNameMappingFunction;
    private Function<T, ImmutableSortedMap<String, String>> nodeToAttributesFunction;

    private Builder(DirectedAcyclicGraph<T> graph) {
      this.graph = graph;
      this.filterPredicate = ignore -> true; // always true predicate
      this.nodeToNameMappingFunction = Objects::toString;
      this.nodeToAttributesFunction = node -> ImmutableSortedMap.of();
    }

    public ThriftOutput.Builder<T> filter(Predicate<T> predicate) {
      filterPredicate = predicate;
      return this;
    }

    public ThriftOutput.Builder<T> nodeToNameMappingFunction(
        Function<T, String> nodeToNameMappingFunction) {
      this.nodeToNameMappingFunction = nodeToNameMappingFunction;
      return this;
    }

    public ThriftOutput.Builder<T> nodeToAttributesFunction(
        Function<T, ImmutableSortedMap<String, String>> nodeToAttributesFunction) {
      this.nodeToAttributesFunction = nodeToAttributesFunction;
      return this;
    }

    public ThriftOutput<T> build() {
      return new ThriftOutput<>(this);
    }
  }

  private ThriftOutput(ThriftOutput.Builder<T> builder) {
    this.graph = builder.graph;
    this.filterPredicate = builder.filterPredicate;
    this.nodeToNameMappingFunction = builder.nodeToNameMappingFunction;
    this.nodeToAttributesFunction = builder.nodeToAttributesFunction;
  }

  /** Writes out the graph in thrift binary format to the given printStream. */
  public void writeOutput(PrintStream printStream) throws IOException {
    writeOutput(ThriftProtocol.BINARY, printStream);
  }

  /**
   * Writes out the graph in thrift format to the given printStream.
   *
   * @param thriftProtocol specific thrift protocol
   */
  public void writeOutput(ThriftProtocol thriftProtocol, PrintStream printStream)
      throws IOException {
    ThriftUtil.serialize(thriftProtocol, toThriftGraph(), printStream);
  }

  private com.facebook.buck.query.thrift.DirectedAcyclicGraph toThriftGraph() {
    com.facebook.buck.query.thrift.DirectedAcyclicGraph thriftDag =
        new com.facebook.buck.query.thrift.DirectedAcyclicGraph();

    ImmutableSet<T> nodes =
        Optional.ofNullable(graph.getNodes()).orElseGet(() -> ImmutableSet.of());
    for (T node : nodes) {
      if (!filterPredicate.test(node)) {
        continue;
      }

      DirectedAcyclicGraphNode fromNode = new DirectedAcyclicGraphNode();
      fromNode.setName(nodeToNameMappingFunction.apply(node));
      nodeToAttributesFunction.apply(node).forEach(fromNode::putToNodeAttributes);
      thriftDag.addToNodes(fromNode);

      ImmutableSet<T> outgoingNodes =
          Optional.ofNullable(graph.getOutgoingNodesFor(node)).orElseGet(() -> ImmutableSet.of());
      for (T outgoingNode : outgoingNodes) {
        if (!filterPredicate.test(outgoingNode)) {
          continue;
        }

        DirectedAcyclicGraphNode toNode = new DirectedAcyclicGraphNode();
        toNode.setName(nodeToNameMappingFunction.apply(outgoingNode));
        addEdge(thriftDag, fromNode, toNode);
      }
    }
    return thriftDag;
  }

  private void addEdge(
      com.facebook.buck.query.thrift.DirectedAcyclicGraph thriftDag,
      DirectedAcyclicGraphNode fromNode,
      DirectedAcyclicGraphNode toNode) {
    DirectedAcyclicGraphEdge graphEdge = new DirectedAcyclicGraphEdge();
    graphEdge.setFromNode(fromNode);
    graphEdge.setToNode(toNode);
    thriftDag.addToEdges(graphEdge);
  }
}
