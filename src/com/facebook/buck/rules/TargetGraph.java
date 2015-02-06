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
import com.facebook.buck.graph.DefaultDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;

/**
 * Represents the graph of {@link com.facebook.buck.rules.TargetNode}s constructed
 * by parsing the build files.
 */
public class TargetGraph extends DefaultDirectedAcyclicGraph<TargetNode<?>> {

  public static final TargetGraph EMPTY = new TargetGraph(
      new MutableDirectedGraph<TargetNode<?>>());

  private final ImmutableMap<BuildTarget, TargetNode<?>> targetsToNodes;

  public TargetGraph(MutableDirectedGraph<TargetNode<?>> graph) {
    super(graph);
    ImmutableMap.Builder<BuildTarget, TargetNode<?>> builder = ImmutableMap.builder();
    for (TargetNode<?> node : graph.getNodes()) {
      builder.put(node.getBuildTarget(), node);
    }
    this.targetsToNodes = builder.build();
  }

  @Nullable
  public TargetNode<?> get(BuildTarget target) {
    return targetsToNodes.get(target);
  }

  public Function<BuildTarget, TargetNode<?>> get() {
    return new Function<BuildTarget, TargetNode<?>>() {
      @Override
      public TargetNode<?> apply(BuildTarget input) {
        return Preconditions.checkNotNull(get(input));
      }
    };
  }

  public Iterable<TargetNode<?>> getAll(Iterable<BuildTarget> targets) {
    return Iterables.transform(
        targets,
        new Function<BuildTarget, TargetNode<?>>() {
          @Override
          public TargetNode<?> apply(BuildTarget input) {
            return Preconditions.checkNotNull(get(input));
          }
        });
  }

  /**
   * Get the subgraph of the the current graph containing the passed in roots and all of their
   * transitive dependencies as nodes. Edges between the included nodes are preserved.
   *
   * @param roots An iterable containing the roots of the new subgraph.
   * @return A subgraph of the current graph.
   */
  public TargetGraph getSubgraph(Iterable<? extends TargetNode<?>> roots) {
    final MutableDirectedGraph<TargetNode<?>> subgraph = new MutableDirectedGraph<>();

    new AbstractBreadthFirstTraversal<TargetNode<?>>(roots) {
      @Override
      public ImmutableSet<TargetNode<?>> visit(TargetNode<?> node) {
        subgraph.addNode(node);
        ImmutableSet<TargetNode<?>> dependencies =
            ImmutableSet.copyOf(getAll(node.getDeps()));
        for (TargetNode<?> dependency : dependencies) {
          subgraph.addEdge(node, dependency);
        }
        return dependencies;
      }
    }.start();

    return new TargetGraph(subgraph);
  }
}
