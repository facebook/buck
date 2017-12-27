/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.MoreMaps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Represents the graph of {@link com.facebook.buck.rules.TargetNode}s constructed by parsing the
 * build files.
 */
public class TargetGraph extends DirectedAcyclicGraph<TargetNode<?, ?>> {

  public static final TargetGraph EMPTY =
      new TargetGraph(new MutableDirectedGraph<>(), ImmutableMap.of());

  private final ImmutableMap<BuildTarget, TargetNode<?, ?>> targetsToNodes;

  private Optional<Integer> cachedHashCode = Optional.empty();

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
      node = targetsToNodes.get(BuildTarget.of(target.getUnflavoredBuildTarget()));
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
      throw new NoSuchNodeException(target);
    }
    return node;
  }

  public Iterable<TargetNode<?, ?>> getAll(Iterable<BuildTarget> targets) {
    return Iterables.transform(targets, this::get);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TargetGraph)) {
      return false;
    }

    TargetGraph other = (TargetGraph) obj;
    if (cachedHashCode.isPresent()
        && other.cachedHashCode.isPresent()
        && !cachedHashCode.get().equals(other.cachedHashCode.get())) {
      return false;
    }

    return targetsToNodes.equals(((TargetGraph) obj).targetsToNodes);
  }

  @Override
  public int hashCode() {
    if (cachedHashCode.isPresent()) {
      return cachedHashCode.get();
    }
    cachedHashCode = Optional.of(targetsToNodes.hashCode());
    return cachedHashCode.get();
  }

  /**
   * Get the subgraph of the the current graph containing the passed in roots and all of their
   * transitive dependencies as nodes. Edges between the included nodes are preserved.
   *
   * @param roots An iterable containing the roots of the new subgraph.
   * @return A subgraph of the current graph.
   */
  public <T> TargetGraph getSubgraph(Iterable<? extends TargetNode<? extends T, ?>> roots) {
    final MutableDirectedGraph<TargetNode<?, ?>> subgraph = new MutableDirectedGraph<>();
    final Map<BuildTarget, TargetNode<?, ?>> index = new HashMap<>();

    new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(roots) {
      @Override
      public Iterable<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {
        subgraph.addNode(node);
        MoreMaps.putCheckEquals(index, node.getBuildTarget(), node);
        if (node.getBuildTarget().isFlavored()) {
          BuildTarget unflavoredBuildTarget =
              BuildTarget.of(node.getBuildTarget().getUnflavoredBuildTarget());
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

  public static class NoSuchNodeException extends RuntimeException
      implements ExceptionWithHumanReadableMessage {

    public NoSuchNodeException(BuildTarget buildTarget) {
      super(
          String.format(
              "Required target for rule '%s' was not found in the target graph.",
              buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
