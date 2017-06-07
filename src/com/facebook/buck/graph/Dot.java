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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Map;

public class Dot<T> {

  private final DirectedAcyclicGraph<T> graph;
  private final String graphName;
  private final Function<T, String> nodeToName;
  private final Function<T, String> nodeToTypeName;
  private final Appendable output;
  private static final Map<String, String> typeColors;

  static {
    typeColors =
        new ImmutableMap.Builder<String, String>() {
          {
            put("android_aar", "springgreen2");
            put("android_library", "springgreen3");
            put("android_resource", "springgreen1");
            put("android_prebuilt_aar", "olivedrab3");
            put("java_library", "indianred1");
            put("prebuilt_jar", "mediumpurple1");
          }
        }.build();
  }

  public Dot(
      DirectedAcyclicGraph<T> graph,
      String graphName,
      Function<T, String> nodeToName,
      Function<T, String> nodeToTypeName,
      Appendable output) {
    this.graph = graph;
    this.graphName = graphName;
    this.nodeToName = nodeToName;
    this.nodeToTypeName = nodeToTypeName;
    this.output = output;
  }

  public void writeOutput() throws IOException {
    output.append("digraph " + graphName + " {\n");

    new AbstractBottomUpTraversal<T, RuntimeException>(graph) {

      @Override
      public void visit(T node) {
        String source = nodeToName.apply(node);
        String sourceType = nodeToTypeName.apply(node);

        try {
          output.append(
              String.format(
                  "  %s [style=filled,color=%s];\n", source, Dot.colorFromType(sourceType)));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
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
      final Function<T, String> nodeToTypeName,
      final Appendable output,
      final boolean bfsSorted)
      throws IOException {
    // Sorting the edges to have deterministic output and be able to test this.
    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    output.append("digraph " + graphName + " {\n");

    if (bfsSorted) {
      for (T root : ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges())) {
        new AbstractBreadthFirstTraversal<T>(root) {

          @Override
          public Iterable<T> visit(T node) {
            if (!nodesToFilter.contains(node)) {
              return ImmutableSet.<T>of();
            }
            String source = nodeToName.apply(node);
            String sourceType = nodeToTypeName.apply(node);
            builder.add(
                String.format(
                    "  %s [style=filled,color=%s];\n", source, Dot.colorFromType(sourceType)));
            ImmutableSortedSet<T> nodes =
                ImmutableSortedSet.copyOf(
                    Sets.filter(graph.getOutgoingNodesFor(node), nodesToFilter::contains));
            for (T sink : nodes) {
              String sinkName = nodeToName.apply(sink);
              builder.add(String.format("  %s -> %s;\n", source, sinkName));
            }
            return nodes;
          }
        }.start();
      }
    } else {
      final ImmutableSortedSet.Builder<String> sortedSetBuilder = ImmutableSortedSet.naturalOrder();
      new AbstractBottomUpTraversal<T, RuntimeException>(graph) {

        @Override
        public void visit(T node) {
          if (!nodesToFilter.contains(node)) {
            return;
          }
          String source = nodeToName.apply(node);
          String sourceType = nodeToTypeName.apply(node);
          sortedSetBuilder.add(
              String.format(
                  "  %s [style=filled,color=%s];\n", source, Dot.colorFromType(sourceType)));
          for (T sink : Sets.filter(graph.getOutgoingNodesFor(node), nodesToFilter::contains)) {
            String sinkName = nodeToName.apply(sink);
            sortedSetBuilder.add(String.format("  %s -> %s;\n", source, sinkName));
          }
        }
      }.traverse();

      builder.addAll(sortedSetBuilder.build());
    }

    for (String line : builder.build()) {
      output.append(line);
    }
    output.append("}\n");
  }

  public static String colorFromType(String type) {
    if (Dot.typeColors.containsKey(type)) {
      return Dot.typeColors.get(type);
    }
    int r = 192 + (type.hashCode() % 64);
    int g = 192 + (type.hashCode() / 64 % 64);
    int b = 192 + (type.hashCode() / 4096 % 64);
    return String.format("\"#%02X%02X%02X\"", r, g, b);
  }
}
