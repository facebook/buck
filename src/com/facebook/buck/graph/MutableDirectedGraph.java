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
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Represents a directed graph with unweighted edges. For a given source and sink node pair, there
 * is at most one directed edge connecting them in the graph.
 * The graph is not required to be connected or acyclic.
 * @param <T> the type of object stored as nodes in this graph
 */
public final class MutableDirectedGraph<T> implements TraversableGraph<T> {

  /**
   * It is possible to have a node in the graph without any edges, which is why we must maintain a
   * separate collection for nodes in the graph, rather than just using the keySet of
   * {@link #outgoingEdges}.
   */
  private final Set<T> nodes;

  /**
   * Represents the edges in the graph.
   * Keys are source nodes; values are corresponding sync nodes.
   */
  private final HashMultimap<T, T> outgoingEdges;

  /**
   * Represents the edges in the graph.
   * Keys are sink nodes; values are corresponding source nodes.
   */
  private final HashMultimap<T, T> incomingEdges;

  /**
   * Creates a new graph with no nodes or edges.
   */
  public MutableDirectedGraph() {
    this.nodes = Sets.newHashSet();
    this.outgoingEdges = HashMultimap.create();
    this.incomingEdges = HashMultimap.create();
  }

  /** @return the number of nodes in the graph */
  public int getNodeCount() {
    return nodes.size();
  }

  /** @return the number of edges in the graph */
  public int getEdgeCount() {
    return outgoingEdges.size();
  }

  /** @return whether the specified node is present in the graph */
  public boolean containsNode(T node) {
    return nodes.contains(node);
  }

  /** @return whether an edge from the source to the sink is present in the graph */
  public boolean containsEdge(T source, T sink) {
    return outgoingEdges.containsEntry(source, sink);
  }

  /**
   * Adds the specified node to the graph.
   */
  public boolean addNode(T node) {
    return nodes.add(node);
  }

  /**
   * Removes the specified node from the graph.
   */
  public boolean removeNode(T node) {
    boolean isRemoved = nodes.remove(node);
    Set<T> nodesReachableFromTheSpecifiedNode = outgoingEdges.removeAll(node);
    for (T reachableNode : nodesReachableFromTheSpecifiedNode) {
      incomingEdges.remove(reachableNode, node);
    }
    return isRemoved;
  }

  /**
   * Adds an edge between {@code source} and {@code sink}. Adds the nodes to the graph if they are
   * not already present.
   */
  public void addEdge(T source, T sink) {
    nodes.add(source);
    nodes.add(sink);
    outgoingEdges.put(source, sink);
    incomingEdges.put(sink, source);
  }

  /**
   * Removes the edge between {@code source} and {@code sink}. This does not remove {@code source}
   * or {@code sink} from the graph. Note that this may leave either {@code source} or
   * {@code sink} as unconnected nodes in the graph.
   */
  public void removeEdge(T source, T sink) {
    outgoingEdges.remove(source, sink);
    incomingEdges.remove(sink, source);
  }

  @Override
  public Iterable<T> getOutgoingNodesFor(T source) {
    return outgoingEdges.get(source);
  }

  @Override
  public Iterable<T> getIncomingNodesFor(T sink) {
    return incomingEdges.get(sink);
  }

  public boolean hasIncomingEdges(T node) {
    return this.incomingEdges.containsKey(node);
  }

  /** @return an unmodifiable view of the nodes in this graph */
  public Set<T> getNodes() {
    return Collections.unmodifiableSet(nodes);
  }

  public boolean isAcyclic() {
    return findCycles().isEmpty();
  }

  public ImmutableSet<ImmutableSet<T>> findCycles() {
    Set<Set<T>> cycles = Sets.filter(tarjan(), new Predicate<Set<T>>() {
      @Override
      public boolean apply(Set<T> stronglyConnectedComponent) {
        return stronglyConnectedComponent.size() > 1;
      }
    });
    Iterable<ImmutableSet<T>> immutableCycles =
        Iterables.transform(cycles, new Function<Set<T>, ImmutableSet<T>>() {
      @Override
      public ImmutableSet<T> apply(Set<T> cycle) {
        return ImmutableSet.copyOf(cycle);
      }
    });

    // Tarjan's algorithm (as pseudo-coded on Wikipedia) does not appear to account for single-node
    // cycles. Therefore, we must check for them exclusively.
    ImmutableSet.Builder<ImmutableSet<T>> builder = ImmutableSet.builder();
    builder.addAll(immutableCycles);
    for (T node : nodes) {
      if (containsEdge(node, node)) {
        builder.add(ImmutableSet.of(node));
      }
    }
    return builder.build();
  }

  /**
   * Implementation of
   * http://en.wikipedia.org/wiki/Tarjan%E2%80%99s_strongly_connected_components_algorithm
   * used to find cycles in the graph.
   */
  private Set<Set<T>> tarjan() {
    Tarjan<T> tarjan = new Tarjan<T>(this);
    return tarjan.findStronglyConnectedComponents();
  }

  @Override
  public Iterable<T> getNodesWithNoIncomingEdges() {
    return Sets.difference(nodes, incomingEdges.keySet());
  }

  @Override
  public Iterable<T> getNodesWithNoOutgoingEdges() {
    return Sets.difference(nodes, outgoingEdges.keySet());
  }

  ImmutableSet<T> createImmutableCopyOfNodes() {
    return ImmutableSet.copyOf(nodes);
  }

  ImmutableSetMultimap<T, T> createImmutableCopyOfOutgoingEdges() {
    return ImmutableSetMultimap.copyOf(outgoingEdges);
  }

  ImmutableSetMultimap<T, T> createImmutableCopyOfIncomingEdges() {
    return ImmutableSetMultimap.copyOf(incomingEdges);
  }

  private static class Tarjan<S> {
    private final MutableDirectedGraph<S> graph;
    private final Map<S, Integer> indexes;
    private final Map<S, Integer> lowlinks;
    private final Deque<S> nodeStack;
    private final Set<Set<S>> stronglyConnectedComponents;
    private int index;

    private Tarjan(MutableDirectedGraph<S> graph) {
      this.graph = graph;
      this.indexes = Maps.newHashMap();
      this.lowlinks = Maps.newHashMap();
      this.nodeStack = Lists.newLinkedList();
      this.stronglyConnectedComponents = Sets.newHashSet();
      this.index = 0;
    }

    public Set<Set<S>> findStronglyConnectedComponents() {
      for (S node : graph.nodes) {
        if (!indexes.containsKey(node)) {
          doStrongConnect(node);
        }
      }
      return stronglyConnectedComponents;
    }

    private void doStrongConnect(final S node) {
      // Set the depth index for node to the smallest unused index.
      indexes.put(node, index);
      lowlinks.put(node, index);
      index++;
      nodeStack.push(node);

      // Consider successors of node.
      for (S sink : graph.getOutgoingNodesFor(node)) {
        if (!indexes.containsKey(sink)) {
          doStrongConnect(sink);
          int lowlink = Math.min(
              Preconditions.checkNotNull(lowlinks.get(node)),
              Preconditions.checkNotNull(lowlinks.get(sink)));
          lowlinks.put(node, lowlink);
        } else if (nodeStack.contains(sink)) {
          // TODO(mbolin): contains() is O(N), consider maintaining an index so it is O(1)?
          int lowlink = Math.min(
              Preconditions.checkNotNull(lowlinks.get(node)),
              Preconditions.checkNotNull(indexes.get(sink)));
          lowlinks.put(node, lowlink);
        }
      }

      // If node is a root node, then pop the stack and generate a strongly connected component.
      if (Preconditions.checkNotNull(lowlinks.get(node)).equals(indexes.get(node))) {
        Set<S> stronglyConnectedComponent = Sets.newHashSet();
        S componentElement;
        do {
          componentElement = nodeStack.pop();
          stronglyConnectedComponent.add(componentElement);
        } while (componentElement != node);
        stronglyConnectedComponents.add(stronglyConnectedComponent);
      }
    }
  }
}
