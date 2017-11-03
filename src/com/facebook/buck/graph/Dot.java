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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public class Dot<T> {

  private final DirectedAcyclicGraph<T> graph;
  private final String graphName;
  private final Function<T, String> nodeToName;
  private final Function<T, String> nodeToTypeName;
  private final boolean bfsSorted;
  private final Predicate<T> shouldContainNode;
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

  public static <T> Builder<T> builder(DirectedAcyclicGraph<T> graph, String graphName) {
    return new Builder<>(graph, graphName);
  }

  /**
   * Builder class for Dot output
   *
   * @param <T>
   */
  public static class Builder<T> {

    private final DirectedAcyclicGraph<T> graph;
    private final String graphName;
    private Function<T, String> nodeToName;
    private Function<T, String> nodeToTypeName;
    private boolean bfsSorted;
    private Predicate<T> shouldContainNode;

    private Builder(DirectedAcyclicGraph<T> graph, String graphName) {
      this.graph = graph;
      this.graphName = graphName;
      nodeToName = Object::toString;
      nodeToTypeName = Object::toString;
      bfsSorted = false;
      shouldContainNode = node -> true;
    }

    public Builder<T> setNodeToName(Function<T, String> func) {
      nodeToName = func;
      return this;
    }

    public Builder<T> setNodeToTypeName(Function<T, String> func) {
      nodeToTypeName = func;
      return this;
    }

    public Builder<T> setBfsSorted(boolean sorted) {
      bfsSorted = sorted;
      return this;
    }

    public Builder<T> setNodesToFilter(Predicate<T> pred) {
      shouldContainNode = pred;
      return this;
    }

    public Dot<T> build() {
      return new Dot<>(this);
    }
  }

  private Dot(Builder<T> builder) {
    this.graph = builder.graph;
    this.graphName = builder.graphName;
    this.nodeToName = builder.nodeToName;
    this.nodeToTypeName = builder.nodeToTypeName;
    this.bfsSorted = builder.bfsSorted;
    this.shouldContainNode = builder.shouldContainNode;
  }

  /** Writes out the graph in dot format to the given output */
  public void writeOutput(Appendable output) throws IOException {
    // Sorting the edges to have deterministic output and be able to test this.
    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    output.append("digraph " + graphName + " {\n");

    if (bfsSorted) {
      for (T root : ImmutableSortedSet.copyOf(graph.getNodesWithNoIncomingEdges())) {
        new AbstractBreadthFirstTraversal<T>(root) {

          @Override
          public Iterable<T> visit(T node) {
            if (!shouldContainNode.test(node)) {
              return ImmutableSet.<T>of();
            }
            builder.add(printNode(node, nodeToName, nodeToTypeName));
            ImmutableSortedSet<T> nodes =
                ImmutableSortedSet.copyOf(
                    Sets.filter(graph.getOutgoingNodesFor(node), shouldContainNode::test));
            for (T sink : nodes) {
              builder.add(printEdge(node, sink, nodeToName));
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
          if (!shouldContainNode.test(node)) {
            return;
          }
          sortedSetBuilder.add(printNode(node, nodeToName, nodeToTypeName));
          for (T sink : Sets.filter(graph.getOutgoingNodesFor(node), shouldContainNode::test)) {
            sortedSetBuilder.add(printEdge(node, sink, nodeToName));
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

  private static String escape(String str) {
    // decide if node name should be escaped according to DOT specification
    // https://en.wikipedia.org/wiki/DOT_(graph_description_language)
    boolean needEscape =
        str.startsWith("//")
            || str.startsWith("#")
            || str.contains(" ")
            || str.contains("\"")
            || str.contains("/*");
    if (!needEscape) {
      return str;
    }
    return "\"" + str.replace("\"", "\\\"") + "\"";
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

  private static <T> String printNode(
      T node, Function<T, String> nodeToName, Function<T, String> nodeToTypeName) {
    String source = nodeToName.apply(node);
    String sourceType = nodeToTypeName.apply(node);
    return String.format(
        "  %s [style=filled,color=%s];\n", escape(source), Dot.colorFromType(sourceType));
  }

  private static <T> String printEdge(T sourceN, T sinkN, Function<T, String> nodeToName) {
    String sourceName = nodeToName.apply(sourceN);
    String sinkName = nodeToName.apply(sinkN);
    return String.format("  %s -> %s;\n", escape(sourceName), escape(sinkName));
  }
}
