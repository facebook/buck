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

package com.facebook.buck.versions;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class VersionedTargetGraph extends TargetGraph {

  private final FlavorSearchTargetNodeFinder nodeFinder;

  private final LoadingCache<BuildTarget, Optional<TargetNode<?>>> internalTargetCache;

  private VersionedTargetGraph(
      DirectedAcyclicGraph<TargetNode<?>> graph, FlavorSearchTargetNodeFinder nodeFinder) {
    super(
        graph,
        graph.getNodes().stream()
            .collect(ImmutableMap.toImmutableMap(TargetNode::getBuildTarget, n -> n)));
    for (TargetNode<?> node : graph.getNodes()) {
      Preconditions.checkArgument(
          !TargetGraphVersionTransformations.getVersionedNode(node).isPresent());
    }
    this.nodeFinder = nodeFinder;
    this.internalTargetCache =
        CacheBuilder.newBuilder()
            .build(CacheLoader.from(target -> Optional.ofNullable(getTargetWithFlavors(target))));
  }

  @Nullable
  @Override
  protected TargetNode<?> getInternal(BuildTarget target) {
    return internalTargetCache.getUnchecked(target).orElse(null);
  }

  private TargetNode<?> getTargetWithFlavors(BuildTarget target) {
    return nodeFinder
        .get(target)
        .map(n -> n.withFlavors(target.getFlavors().getSet()))
        .orElse(null);
  }

  public static VersionedTargetGraph.Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private final DirectedAcyclicGraph.Builder<TargetNode<?>> graph =
        DirectedAcyclicGraph.concurrentBuilder();
    private final Map<BuildTarget, TargetNode<?>> index = new ConcurrentHashMap<>();

    private Builder() {}

    public Builder addNode(BuildTarget baseTarget, TargetNode<?> node) {
      index.put(baseTarget, node);
      graph.addNode(node);
      return this;
    }

    @Nullable
    private static TargetNode<?> getVersionedSubGraphParent(
        TraversableGraph<TargetNode<?>> graph, TargetNode<?> node) {

      // If this node is a root node in the versioned subgraph, there's no dependent and we return
      // `null`.
      if (!TargetGraphVersionTransformations.isVersionPropagator(node)
          && !TargetGraphVersionTransformations.getVersionedNode(node).isPresent()) {
        return null;
      }

      // Otherwise, return any dependent node.  For reproducibility/determinism, we sort the list
      // of dependents and return the first one.
      return Iterables.getFirst(ImmutableSortedSet.copyOf(graph.getIncomingNodesFor(node)), null);
    }

    private static HumanReadableException getUnexpectedVersionedNodeError(
        TraversableGraph<TargetNode<?>> graph, TargetNode<?> node) {
      String msg =
          String.format(
              "Found versioned node %s from unversioned, top-level target:%s",
              node.getBuildTarget(), System.lineSeparator());
      ArrayList<TargetNode<?>> trace = new ArrayList<>();
      for (TargetNode<?> n = node; n != null; n = getVersionedSubGraphParent(graph, n)) {
        trace.add(n);
      }
      msg +=
          trace.stream()
              .map(n -> String.format("    %s (%s)", n, n.getRuleType()))
              .collect(Collectors.joining(" depended on by" + System.lineSeparator()));
      return new HumanReadableException(msg);
    }

    private static void checkGraph(TraversableGraph<TargetNode<?>> graph) {
      for (TargetNode<?> node : graph.getNodes()) {
        if (TargetGraphVersionTransformations.getVersionedNode(node).isPresent()) {
          throw getUnexpectedVersionedNodeError(graph, node);
        }
      }
    }

    public Builder addEdge(TargetNode<?> src, TargetNode<?> dst) {
      graph.addEdge(src, dst);
      return this;
    }

    public VersionedTargetGraph build() {
      DirectedAcyclicGraph<TargetNode<?>> graph = this.graph.build();
      checkGraph(graph);
      return new VersionedTargetGraph(
          graph, ImmutableFlavorSearchTargetNodeFinder.ofImpl(ImmutableMap.copyOf(index)));
    }
  }
}
