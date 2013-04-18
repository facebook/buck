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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public class DefaultImmutableDirectedAcyclicGraph<T> implements ImmutableDirectedAcyclicGraph<T> {

  private final MutableDirectedGraph<T> graph;

  public DefaultImmutableDirectedAcyclicGraph(MutableDirectedGraph<T> graph) {
    Preconditions.checkNotNull(graph);
    Preconditions.checkArgument(graph.isAcyclic());
    this.graph = new MutableDirectedGraph<T>(graph);
  }

  @Override
  public ImmutableSet<T> getOutgoingNodesFor(T source) {
    return graph.getOutgoingNodesFor(source);
  }

  @Override
  public ImmutableSet<T> getIncomingNodesFor(T sink) {
    return graph.getIncomingNodesFor(sink);
  }

  @Override
  public ImmutableSet<T> getNodesWithNoOutgoingEdges() {
    return graph.getNodesWithNoOutgoingEdges();
  }

  @Override
  public ImmutableSet<T> getNodesWithNoIncomingEdges() {
    return graph.getNodesWithNoIncomingEdges();
  }

  public String toDebugString() {
    StringBuilder builder = new StringBuilder();
    builder.append("Number of nodes: " + graph.getNodes().size() + "\n");
    for (T node : graph.getNodes()) {
      for (T sink : graph.getOutgoingNodesFor(node)) {
        builder.append(String.format("%s => %s\n", node, sink));
      }
    }
    return builder.toString();
  }

  /** @return the number of nodes in the graph */
  public int getNodeCount() {
    return graph.getNodeCount();
  }

  /** @return an unmodifiable view of the nodes in this graph */
  public Iterable<T> getNodes() {
    return graph.getNodes();
  }
}
