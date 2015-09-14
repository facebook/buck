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

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;

public class Dot<T> {

  private final DirectedAcyclicGraph<T> graph;
  private final String graphName;
  private final Function<T, String> nodeToName;
  private final Appendable output;

  public Dot(
      DirectedAcyclicGraph<T> graph,
      String graphName,
      Function<T, String> nodeToName,
      Appendable output) {
    this.graph = graph;
    this.graphName = graphName;
    this.nodeToName = nodeToName;
    this.output = output;
  }

  public void writeOutput() throws IOException {
    output.append("digraph " + graphName + " {\n");

    new AbstractBottomUpTraversal<T, Object>(graph) {

      @Override
      public void visit(T node) {
        String source = nodeToName.apply(node);
        for (T sink : graph.getOutgoingNodesFor(node)) {
          String sinkName = nodeToName.apply(sink);
          try {
            output.append(String.format("  %s -> %s;\n", source, sinkName));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }.traverse();

    output.append("}\n");
  }

  public static <T> void writeSubgraphOutput(
      final DirectedAcyclicGraph<T> graph,
      final String graphName,
      final ImmutableSet<T> nodesToFilter,
      final Function<T, String> nodeToName,
      final Appendable output)
      throws IOException {
    // Sorting the edges to have deterministic output and be able to test this.
    final ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
    output.append("digraph " + graphName + " {\n");

    new AbstractBottomUpTraversal<T, Object>(graph) {

      @Override
      public void visit(T node) {
        if (!nodesToFilter.contains(node)) {
          return;
        }
        String source = nodeToName.apply(node);
        for (T sink : Sets.filter(graph.getOutgoingNodesFor(node), Predicates.in(nodesToFilter))) {
          String sinkName = nodeToName.apply(sink);
          builder.add(String.format("  %s -> %s;\n", source, sinkName));
        }
      }
    }.traverse();

    for (String line : builder.build()) {
      output.append(line);
    }
    output.append("}\n");
  }
}
