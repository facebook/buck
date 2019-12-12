/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.util.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Dot<T> {

  private static final Pattern VALID_ID_PATTERN = Pattern.compile("[a-zA-Z\200-\377_0-9]+");

  private final DirectedAcyclicGraph<T> graph;
  private final String graphName;
  private final Function<T, String> nodeToName;
  private final Function<T, String> nodeToTypeName;
  private final Function<T, ImmutableSortedMap<String, String>> nodeToAttributes;
  private final OutputOrder outputOrder;
  private final Predicate<T> shouldContainNode;
  private final boolean compactMode;
  private final Map<T, Integer> nodeToNodeId;
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
    private boolean compactMode;
    private Function<T, String> nodeToName;
    private Function<T, String> nodeToTypeName;
    private Function<T, ImmutableSortedMap<String, String>> nodeToAttributes;
    private OutputOrder outputOrder;
    private Predicate<T> shouldContainNode;

    private Builder(DirectedAcyclicGraph<T> graph, String graphName) {
      this.graph = graph;
      this.graphName = graphName;
      nodeToName = Object::toString;
      nodeToTypeName = Object::toString;
      outputOrder = OutputOrder.SORTED;
      shouldContainNode = node -> true;
      nodeToAttributes = node -> ImmutableSortedMap.of();
    }

    public Builder<T> setNodeToName(Function<T, String> func) {
      nodeToName = func;
      return this;
    }

    public Builder<T> setNodeToTypeName(Function<T, String> func) {
      nodeToTypeName = func;
      return this;
    }

    public Builder<T> setOutputOrder(OutputOrder outputOrder) {
      this.outputOrder = outputOrder;
      return this;
    }

    public Builder<T> setNodesToFilter(Predicate<T> pred) {
      shouldContainNode = pred;
      return this;
    }

    public Builder<T> setCompactMode(boolean value) {
      compactMode = value;
      return this;
    }

    /**
     * Configures a function to be used to extract additional attributes to include when rendering
     * graph nodes.
     *
     * <p>In order ot prevent collisions, all attribute names are prefixed with {@code buck_}. They
     * are also escaped in order to be compatible with the <a
     * href="https://graphviz.gitlab.io/_pages/doc/info/lang.html">Dot format</a>.
     */
    public Builder<T> setNodeToAttributes(Function<T, ImmutableSortedMap<String, String>> func) {
      nodeToAttributes = func;
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
    this.outputOrder = builder.outputOrder;
    this.shouldContainNode = builder.shouldContainNode;
    this.nodeToAttributes = builder.nodeToAttributes;
    this.compactMode = builder.compactMode;
    this.nodeToNodeId = new HashMap<>();
  }

  private String getNodeId(T node) {
    if (!this.nodeToNodeId.containsKey(node)) {
      this.nodeToNodeId.put(node, this.nodeToNodeId.size() + 1);
    }
    return String.valueOf(this.nodeToNodeId.get(node));
  }

  /** Writes out the graph in dot format to the given output */
  public void writeOutput(Appendable output) throws IOException {
    // Sorting the edges to have deterministic output and be able to test this.
    output.append("digraph ").append(graphName).append(" {");
    output.append(System.lineSeparator());

    switch (outputOrder) {
      case BFS:
        {
          new AbstractBreadthFirstThrowingTraversal<T, IOException>(
              graph.getNodesWithNoIncomingEdges()) {

            @Override
            public Iterable<T> visit(T node) throws IOException {
              ImmutableSortedSet<T> deps =
                  ImmutableSortedSet.copyOf(graph.getOutgoingNodesFor(node));
              if (shouldContainNode.test(node)) {
                output.append(
                    printNode(
                        node,
                        Dot.this::getNodeId,
                        nodeToName,
                        nodeToTypeName,
                        nodeToAttributes,
                        compactMode));
                for (T dep : Sets.filter(deps, shouldContainNode::test)) {
                  output.append(printEdge(node, dep, nodeToName, Dot.this::getNodeId, compactMode));
                }
              }
              return deps;
            }
          }.start();
          break;
        }
      case SORTED:
        {
          for (T node : ImmutableSortedSet.copyOf(graph.getNodes())) {
            if (shouldContainNode.test(node)) {
              output.append(
                  printNode(
                      node,
                      Dot.this::getNodeId,
                      nodeToName,
                      nodeToTypeName,
                      nodeToAttributes,
                      compactMode));
              for (T dep :
                  Sets.filter(
                      ImmutableSortedSet.copyOf(graph.getOutgoingNodesFor(node)),
                      shouldContainNode::test)) {
                output.append(printEdge(node, dep, nodeToName, Dot.this::getNodeId, compactMode));
              }
            }
          }
          break;
        }
    }

    output.append("}");
    output.append(System.lineSeparator());
  }

  private static String escape(String str) {
    // decide if node name should be escaped according to DOT specification
    // https://en.wikipedia.org/wiki/DOT_(graph_description_language)
    boolean needEscape =
        !VALID_ID_PATTERN.matcher(str).matches()
            || str.isEmpty()
            || Character.isDigit(str.charAt(0));
    if (!needEscape) {
      return str;
    }
    return Escaper.Quoter.DOUBLE.quote(str);
  }

  private static String colorFromType(String type) {
    if (Dot.typeColors.containsKey(type)) {
      return Dot.typeColors.get(type);
    }
    int r = 192 + (type.hashCode() % 64);
    int g = 192 + (type.hashCode() / 64 % 64);
    int b = 192 + (type.hashCode() / 4096 % 64);
    return String.format("\"#%02X%02X%02X\"", r, g, b);
  }

  private static <T> String printNode(
      T node,
      Function<T, String> nodeToId,
      Function<T, String> nodeToName,
      Function<T, String> nodeToTypeName,
      Function<T, ImmutableSortedMap<String, String>> nodeToAttributes,
      boolean compactMode) {
    String source = nodeToName.apply(node);
    String sourceType = nodeToTypeName.apply(node);
    String extraAttributes = "";
    ImmutableSortedMap<String, String> nodeAttributes = nodeToAttributes.apply(node);

    String labelAttribute = "";
    if (compactMode) {
      labelAttribute = ",label=" + escape(source);
      source = nodeToId.apply(node); // Don't need to escape numeric IDs
    } else {
      source = escape(source);
    }

    if (!nodeAttributes.isEmpty()) {
      extraAttributes =
          ","
              + nodeAttributes.entrySet().stream()
                  .map(entry -> escape("buck_" + entry.getKey()) + "=" + escape(entry.getValue()))
                  .collect(Collectors.joining(","));
    }
    return String.format(
        "  %s [style=filled,color=%s%s%s];%n",
        source, Dot.colorFromType(sourceType), labelAttribute, extraAttributes);
  }

  private static <T> String printEdge(
      T sourceN,
      T sinkN,
      Function<T, String> nodeToName,
      Function<T, String> nodeToId,
      boolean compactMode) {
    // Don't need to escape the names in compact mode because they're just numbers
    String sourceName = compactMode ? nodeToId.apply(sourceN) : escape(nodeToName.apply(sourceN));
    String sinkName = compactMode ? nodeToId.apply(sinkN) : escape(nodeToName.apply(sinkN));

    return String.format("  %s -> %s;%n", sourceName, sinkName);
  }

  /** How to print the dot graph. */
  public enum OutputOrder {
    /** Generate the dot output in the order of a breadth-first traversal of the graph. */
    BFS,

    /** Generate the dot output in the sorted natural order of the nodes. */
    SORTED,
  }
}
