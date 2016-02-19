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
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Represents the graph of {@link com.facebook.buck.rules.TargetNode}s constructed
 * by parsing the build files.
 */
public class TargetGraph extends DefaultDirectedAcyclicGraph<TargetNode<?>> {

  public static final TargetGraph EMPTY = new TargetGraph(
      new MutableDirectedGraph<TargetNode<? extends AbstractDescriptionArg>>(),
      ImmutableMap.<BuildTarget, TargetNode<?>>of());

  private final ImmutableMap<BuildTarget, TargetNode<?>> targetsToNodes;

  public TargetGraph(
      MutableDirectedGraph<TargetNode<? extends AbstractDescriptionArg>> graph,
      ImmutableMap<BuildTarget, TargetNode<? extends AbstractDescriptionArg>> index) {
    super(graph);
    this.targetsToNodes = index;
  }

  @Nullable
  public TargetNode<? extends AbstractDescriptionArg> getInternal(BuildTarget target) {
    TargetNode<?> node = targetsToNodes.get(target);
    if (node == null) {
      node = targetsToNodes.get(BuildTarget.of(target.getUnflavoredBuildTarget()));
      if (node == null) {
        return null;
      }
      return node.withFlavors(target.getFlavors());
    }
    return node;
  }

  public Optional<TargetNode<? extends AbstractDescriptionArg>> getOptional(BuildTarget target) {
    return Optional.<TargetNode<?>>fromNullable(getInternal(target));
  }

  public TargetNode<?> get(BuildTarget target) {
    TargetNode<?> node = getInternal(target);
    if (node == null) {
      throw new NoSuchNodeException(target);
    }
    return node;
  }

  public Function<BuildTarget, TargetNode<? extends AbstractDescriptionArg>> get() {
    return new Function<BuildTarget, TargetNode<?>>() {
      @Override
      public TargetNode<?> apply(BuildTarget input) {
        return get(input);
      }
    };
  }

  public Iterable<TargetNode<?>> getAll(Iterable<BuildTarget> targets) {
    return Iterables.transform(
        targets,
        new Function<BuildTarget, TargetNode<?>>() {
          @Override
          public TargetNode<?> apply(BuildTarget input) {
            return get(input);
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
  public TargetGraph getSubgraph(
      Iterable<? extends TargetNode<? extends AbstractDescriptionArg>> roots) {
    final MutableDirectedGraph<TargetNode<? extends AbstractDescriptionArg>> subgraph =
        new MutableDirectedGraph<>();
    final Map<BuildTarget, TargetNode<? extends AbstractDescriptionArg>> index = new HashMap<>();

    new AbstractBreadthFirstTraversal<TargetNode<? extends AbstractDescriptionArg>>(roots) {
      @Override
      public ImmutableSet<TargetNode<? extends AbstractDescriptionArg>>visit(
          TargetNode<? extends AbstractDescriptionArg> node) {
        subgraph.addNode(node);
        MoreMaps.putCheckEquals(index, node.getBuildTarget(), node);
        if (node.getBuildTarget().isFlavored()) {
          BuildTarget unflavoredBuildTarget = BuildTarget.of(
              node.getBuildTarget().getUnflavoredBuildTarget());
          MoreMaps.putCheckEquals(
              index,
              unflavoredBuildTarget,
              targetsToNodes.get(unflavoredBuildTarget));
        }
        ImmutableSet<TargetNode<? extends AbstractDescriptionArg>> dependencies =
            ImmutableSet.copyOf(getAll(node.getDeps()));
        for (TargetNode<? extends AbstractDescriptionArg> dependency : dependencies) {
          subgraph.addEdge(node, dependency);
        }
        return dependencies;
      }
    }.start();

    return new TargetGraph(subgraph, ImmutableMap.copyOf(index));
  }

  @SuppressWarnings("serial")
  public static class NoSuchNodeException extends RuntimeException
      implements ExceptionWithHumanReadableMessage {

    public NoSuchNodeException(BuildTarget buildTarget) {
      super(String.format(
              "Required target for rule '%s' was not found in the target graph.",
              buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
