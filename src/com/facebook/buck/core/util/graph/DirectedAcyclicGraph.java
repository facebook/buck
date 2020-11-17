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

package com.facebook.buck.core.util.graph;

import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteLock;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.function.Supplier;

public class DirectedAcyclicGraph<T> implements TraversableGraph<T> {

  private final ImmutableSet<T> nodes;
  private final ImmutableMap<T, ImmutableSet<T>> outgoingEdges;
  private final Supplier<ImmutableMap<T, ImmutableCollection<T>>> incomingEdges;

  /** Constructor used by builders. */
  private DirectedAcyclicGraph(
      ImmutableSet<T> nodes, ImmutableMap<T, ImmutableSet<T>> outgoingEdges) {
    Preconditions.checkState(
        TraversableGraphs.isAcyclic(nodes, n -> outgoingEdges.getOrDefault(n, ImmutableSet.of())),
        "Graph must by acyclic");
    this.nodes = nodes;
    this.outgoingEdges = outgoingEdges;
    this.incomingEdges =
        Suppliers.memoize(() -> computeIncomingEdges(this.nodes, this.outgoingEdges));
  }

  /** Copy constructor. */
  protected DirectedAcyclicGraph(DirectedAcyclicGraph<T> other) {
    this.nodes = other.nodes;
    this.outgoingEdges = other.outgoingEdges;
    this.incomingEdges = other.incomingEdges;
  }

  /**
   * Legacy constructor to convert form {@link MutableDirectedGraph}.
   *
   * @deprecated Use {@link DirectedAcyclicGraph#serialBuilder()} or {@link
   *     DirectedAcyclicGraph#concurrentBuilder()} to build a graph.
   */
  @Deprecated
  @SuppressWarnings({"unchecked", "rawtypes"})
  public DirectedAcyclicGraph(MutableDirectedGraph<T> graph) {
    Preconditions.checkState(
        TraversableGraphs.isAcyclic(graph.getNodes(), graph::getOutgoingNodesFor),
        "Graph must by acyclic");
    this.nodes = graph.createImmutableCopyOfNodes();
    this.outgoingEdges = graph.createImmutableCopyOfOutgoingEdges();
    this.incomingEdges =
        Suppliers.ofInstance((ImmutableMap) graph.createImmutableCopyOfIncomingEdges());
  }

  /**
   * Consume a {@link Map} of map of nodes to builders and produce an {@link ImmutableMap} of their
   * built collections. The input map will be empty once complete.
   *
   * @return an {@link ImmutableMap} built from a {@link Map} of builders.
   */
  private static <T, C extends ImmutableCollection<T>, B> ImmutableMap<T, C> toImmutableMap(
      Function<B, C> build, Map<T, B> map) {
    ImmutableMap.Builder<T, C> builder = ImmutableMap.builderWithExpectedSize(map.size());
    for (Iterator<Map.Entry<T, B>> itr = map.entrySet().iterator(); itr.hasNext(); ) {
      Map.Entry<T, B> entry = itr.next();
      // Remove the builder from the map before building it.  This allows the builder to be garbage
      // collected while we're processing remaining builders, to help prevent this function from
      // requiring 2x the size of the input map to run.
      itr.remove();
      builder.put(entry.getKey(), Preconditions.checkNotNull(build.apply(entry.getValue())));
    }
    return builder.build();
  }

  /** @return the map of incoming node edges, computed from the map of outgoing node edges. */
  private static <T> ImmutableMap<T, ImmutableCollection<T>> computeIncomingEdges(
      ImmutableSet<T> nodes, ImmutableMap<T, ImmutableSet<T>> outgoingEdges) {
    Map<T, ImmutableList.Builder<T>> builder = new HashMap<>();
    for (T source : nodes) {
      for (T sink : outgoingEdges.getOrDefault(source, ImmutableSet.of())) {
        builder.computeIfAbsent(sink, n -> ImmutableList.builder()).add(source);
      }
    }
    return toImmutableMap(ImmutableList.Builder::build, builder);
  }

  @Override
  public ImmutableSet<T> getOutgoingNodesFor(T source) {
    return outgoingEdges.getOrDefault(source, ImmutableSet.of());
  }

  @Override
  public ImmutableCollection<T> getIncomingNodesFor(T sink) {
    return incomingEdges.get().getOrDefault(sink, ImmutableSet.of());
  }

  @Override
  public ImmutableSet<T> getNodesWithNoOutgoingEdges() {
    return ImmutableSet.copyOf(Sets.difference(nodes, outgoingEdges.keySet()));
  }

  @Override
  public ImmutableSet<T> getNodesWithNoIncomingEdges() {
    return ImmutableSet.copyOf(Sets.difference(nodes, incomingEdges.get().keySet()));
  }

  @Override
  public ImmutableSet<T> getNodes() {
    return nodes;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof DirectedAcyclicGraph)) {
      return false;
    }

    DirectedAcyclicGraph<?> that = (DirectedAcyclicGraph<?>) other;
    return Objects.equals(this.nodes, that.nodes)
        && Objects.equals(this.outgoingEdges, that.outgoingEdges);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodes, outgoingEdges, incomingEdges);
  }

  public static <T> Builder<T> serialBuilder() {
    return new DirectedAcyclicGraph.SingleThreadedBuilder<>();
  }

  public static <T> Builder<T> concurrentBuilder() {
    return new DirectedAcyclicGraph.MultiThreadedBuilder<>();
  }

  /** Base class for graph builders which avoid copying intermediate sets/maps. */
  public abstract static class Builder<T> {

    // To avoid copying, we only support a single use of this builder.
    protected boolean finished = false;

    protected Collection<T> nodes;
    protected Map<T, Collection<T>> outgoingEdges;

    public Builder(Collection<T> nodes, Map<T, Collection<T>> outgoingEdges) {
      this.nodes = nodes;
      this.outgoingEdges = outgoingEdges;
    }

    /** Insert node into the graph. */
    public Builder<T> addNode(T node) {
      Preconditions.checkState(!finished);
      nodes.add(node);
      return this;
    }

    protected void putEdge(Collection<T> edges, T edge) {
      edges.add(edge);
    }

    /** Add an edge from {@code source} to {@code sink}. */
    public Builder<T> addEdge(T source, T sink) {
      Preconditions.checkState(!source.equals(sink));
      Preconditions.checkState(!finished);
      nodes.add(source);
      nodes.add(sink);
      putEdge(outgoingEdges.computeIfAbsent(source, s -> new ArrayList<>()), sink);
      return this;
    }

    /** Consumes the given node collection and converts to an {@link ImmutableSet}. */
    private ImmutableSet<T> buildImmutableSet(Collection<T> nodes) {
      // NOTE: `ImmutableSet.copyOf()` doesn't pre-size the builder, so we do it ourselves as, in
      // practice, we don't add duplicate nodes/edges.
      ImmutableSet.Builder<T> builder =
          ImmutableSet.<T>builderWithExpectedSize(nodes.size()).addAll(nodes);

      // Clear out the existing node list before building, to potentially free up memory.
      nodes.clear();
      if (nodes instanceof ArrayList) {
        ((ArrayList<T>) nodes).trimToSize();
      }

      return builder.build();
    }

    /**
     * Build a {@link DirectedAcyclicGraph}. This builder cannot be modified once this has been
     * called.
     */
    public DirectedAcyclicGraph<T> build() {
      Preconditions.checkState(!finished);
      finished = true;

      // Create an immutable copy of the nodes, and clear out the builder set to free up memory.
      ImmutableSet<T> nodes = buildImmutableSet(this.nodes);
      this.nodes = null;

      // Create an immutable copy of the outgoing edges, which frees up the builder map to free up
      // memory.
      ImmutableMap<T, ImmutableSet<T>> outgoingEdges =
          DirectedAcyclicGraph.toImmutableMap(this::buildImmutableSet, this.outgoingEdges);
      this.outgoingEdges = null;

      return new DirectedAcyclicGraph<>(nodes, outgoingEdges);
    }
  }

  /** Builder optimized for single-threaded use. */
  private static class SingleThreadedBuilder<T> extends Builder<T> {
    private SingleThreadedBuilder() {
      super(new ArrayList<>(), new HashMap<>());
    }
  }

  /** A thread-safe builder. */
  private static class MultiThreadedBuilder<T> extends Builder<T> {

    // Lock to make sure concurrent invocations can't modify collections while `build()` is running.
    private final AutoCloseableReadWriteLock lock = new AutoCloseableReadWriteLock();

    private MultiThreadedBuilder() {
      super(new ConcurrentLinkedQueue<>(), new ConcurrentHashMap<>());
    }

    @Override
    protected void putEdge(Collection<T> edges, T edge) {
      synchronized (edges) {
        super.putEdge(edges, edge);
      }
    }

    @Override
    public Builder<T> addNode(T node) {
      try (AutoCloseableLock readLock = lock.readLock()) {
        return super.addNode(node);
      }
    }

    @Override
    public Builder<T> addEdge(T source, T sink) {
      try (AutoCloseableLock readLock = lock.readLock()) {
        return super.addEdge(source, sink);
      }
    }

    @Override
    public DirectedAcyclicGraph<T> build() {
      try (AutoCloseableLock writeLock = lock.writeLock()) {
        return super.build();
      }
    }
  }
}
