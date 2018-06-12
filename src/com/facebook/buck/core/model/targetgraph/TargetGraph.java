/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.util.MoreMaps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Represents the graph of {@link TargetNode}s constructed by parsing the build files. */
public class TargetGraph extends DirectedAcyclicGraph<TargetNode<?, ?>> {

  public static final TargetGraph EMPTY =
      new TargetGraph(new MutableDirectedGraph<>(), ImmutableMap.of());

  private final ImmutableMap<BuildTarget, TargetNode<?, ?>> targetsToNodes;

  private OptionalInt cachedHashCode = OptionalInt.empty();

  public TargetGraph(
      MutableDirectedGraph<TargetNode<?, ?>> graph,
      ImmutableMap<BuildTarget, TargetNode<?, ?>> index) {
    super(graph);
    this.targetsToNodes = index;

    verifyVisibilityIntegrity();
  }

  private void verifyVisibilityIntegrity() {
    for (TargetNode<?, ?> node : getNodes()) {
      for (TargetNode<?, ?> dep : getOutgoingNodesFor(node)) {
        dep.isVisibleToOrThrow(node);
      }
    }
  }

  @Nullable
  protected TargetNode<?, ?> getInternal(BuildTarget target) {
    TargetNode<?, ?> node = targetsToNodes.get(target);
    if (node == null) {
      node = targetsToNodes.get(ImmutableBuildTarget.of(target.getUnflavoredBuildTarget()));
      if (node == null) {
        return null;
      }
      return node.copyWithFlavors(target.getFlavors());
    }
    return node;
  }

  public Optional<TargetNode<?, ?>> getOptional(BuildTarget target) {
    return Optional.ofNullable(getInternal(target));
  }

  public TargetNode<?, ?> get(BuildTarget target) {
    TargetNode<?, ?> node = getInternal(target);
    if (node == null) {
      throw new NoSuchTargetException(target);
    }
    return node;
  }

  /**
   * Returns the target node for the exact given target, if it exists in the graph.
   *
   * <p>If given a flavored target, and the target graph doesn't contain that flavored target, this
   * method will always return null, unlike {@code getOptional}, which may return the node for a
   * differently flavored target ({@see VersionedTargetGraph#getInternal}).
   */
  public Optional<TargetNode<?, ?>> getExactOptional(BuildTarget target) {
    return Optional.ofNullable(targetsToNodes.get(target));
  }

  public Iterable<TargetNode<?, ?>> getAll(Iterable<BuildTarget> targets) {
    return Iterables.transform(targets, this::get);
  }

  /** Returns a stream of target nodes corresponding to passed build targets. */
  public Stream<TargetNode<?, ?>> streamAll(Collection<BuildTarget> targets) {
    return targets.stream().map(this::get);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TargetGraph)) {
      return false;
    }

    TargetGraph other = (TargetGraph) obj;
    if (cachedHashCode.isPresent()
        && other.cachedHashCode.isPresent()
        && cachedHashCode.getAsInt() != other.cachedHashCode.getAsInt()) {
      return false;
    }

    return targetsToNodes.equals(((TargetGraph) obj).targetsToNodes);
  }

  @Override
  public int hashCode() {
    if (cachedHashCode.isPresent()) {
      return cachedHashCode.getAsInt();
    }
    int hashCode = targetsToNodes.hashCode();
    cachedHashCode = OptionalInt.of(hashCode);
    return hashCode;
  }

  /**
   * Get the subgraph of the the current graph containing the passed in roots and all of their
   * transitive dependencies as nodes. Edges between the included nodes are preserved.
   *
   * @param roots An iterable containing the roots of the new subgraph.
   * @return A subgraph of the current graph.
   */
  public <T> TargetGraph getSubgraph(Iterable<? extends TargetNode<? extends T, ?>> roots) {
    MutableDirectedGraph<TargetNode<?, ?>> subgraph = new MutableDirectedGraph<>();
    Map<BuildTarget, TargetNode<?, ?>> index = new HashMap<>();

    new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(roots) {
      @Override
      public Iterable<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {
        subgraph.addNode(node);
        MoreMaps.putCheckEquals(index, node.getBuildTarget(), node);
        if (node.getBuildTarget().isFlavored()) {
          BuildTarget unflavoredBuildTarget =
              ImmutableBuildTarget.of(node.getBuildTarget().getUnflavoredBuildTarget());
          MoreMaps.putCheckEquals(
              index, unflavoredBuildTarget, targetsToNodes.get(unflavoredBuildTarget));
        }
        ImmutableSet<TargetNode<?, ?>> dependencies =
            ImmutableSet.copyOf(getAll(node.getParseDeps()));
        for (TargetNode<?, ?> dependency : dependencies) {
          subgraph.addEdge(node, dependency);
        }
        return dependencies;
      }
    }.start();

    return new TargetGraph(subgraph, ImmutableMap.copyOf(index));
  }

  public int getSize() {
    return getNodes().size();
  }
}
