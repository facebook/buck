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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class TargetGraphAndTargets {
  private final TargetGraph targetGraph;
  private final ImmutableSet<TargetNode<?>> projectRoots;
  private final ImmutableSet<TargetNode<?>> associatedTests;

  private TargetGraphAndTargets(
      TargetGraph targetGraph,
      Iterable<TargetNode<?>> projectRoots,
      Iterable<TargetNode<?>> associatedTests) {
    this.targetGraph = targetGraph;
    this.projectRoots = ImmutableSet.copyOf(projectRoots);
    this.associatedTests = ImmutableSet.copyOf(associatedTests);
  }

  public TargetGraph getTargetGraph() {
    return targetGraph;
  }

  public ImmutableSet<TargetNode<?>> getProjectRoots() {
    return projectRoots;
  }

  public ImmutableSet<TargetNode<?>> getAssociatedTests() {
    return associatedTests;
  }

  /**
   * @param buildTargets The set of targets for which we would like to find tests
   * @param projectGraph A TargetGraph containing all nodes and their tests.
   * @return A set of all test targets that test any of {@code buildTargets} or their dependencies.
   */
  public static ImmutableSet<BuildTarget> getExplicitTestTargets(
      ImmutableSet<BuildTarget> buildTargets,
      TargetGraph projectGraph) {
    ImmutableSet<TargetNode<?>> projectRoots = checkAndGetTargetNodes(buildTargets, projectGraph);
    return getExplicitTestTargets(projectGraph.getSubgraph(projectRoots).getNodes());
  }

  /**
   * @param nodes Nodes whose test targets we would like to find
   * @return A set of all test targets that test the targets in {@code nodes}.
   */
  public static ImmutableSet<BuildTarget> getExplicitTestTargets(
      Iterable<TargetNode<?>> nodes) {
    return FluentIterable
        .from(nodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<BuildTarget>>() {
              @Override
              public Iterable<BuildTarget> apply(TargetNode<?> node) {
                return TargetNodes.getTestTargetsForNode(node);
              }
            })
        .toSet();
  }

  private static ImmutableSet<TargetNode<?>> checkAndGetTargetNodes(
      ImmutableSet<BuildTarget> buildTargets,
      TargetGraph projectGraph) {
    ImmutableSet.Builder<TargetNode<?>> targetNodesBuilder = ImmutableSet.builder();
    for (BuildTarget target : buildTargets) {
      TargetNode<?> targetNode = projectGraph.get(target);
      if (targetNode == null) {
        throw new HumanReadableException("Target '%s' does not exist.", target);
      }
      targetNodesBuilder.add(targetNode);
    }
    return targetNodesBuilder.build();
  }

  public static TargetGraphAndTargets create(
      ImmutableSet<BuildTarget> graphRoots,
      TargetGraph projectGraph,
      AssociatedTargetNodePredicate associatedProjectPredicate,
      boolean isWithTests,
      ImmutableSet<BuildTarget> explicitTests) {
    // Get the roots of the main graph. This contains all the targets in the project slice, or all
    // the valid project roots if a project slice is not specified.
    ImmutableSet<TargetNode<?>> projectRoots = checkAndGetTargetNodes(graphRoots, projectGraph);

    // Optionally get the roots of the test graph. This contains all the tests that cover the roots
    // of the main graph or their dependencies.
    ImmutableSet<TargetNode<?>> associatedTests = ImmutableSet.of();
    if (isWithTests) {
      AssociatedTargetNodePredicate associatedTestsPredicate = new AssociatedTargetNodePredicate() {
        @Override
        public boolean apply(TargetNode<?> targetNode, TargetGraph targetGraph) {
          if (!targetNode.getType().isTestRule()) {
            return false;
          }
          ImmutableSortedSet<BuildTarget> sourceUnderTest;
          if (targetNode.getConstructorArg() instanceof HasSourceUnderTest) {
            HasSourceUnderTest argWithSourceUnderTest =
                (HasSourceUnderTest) targetNode.getConstructorArg();
            sourceUnderTest = argWithSourceUnderTest.getSourceUnderTest();
          } else {
            return false;
          }

          for (BuildTarget buildTargetUnderTest : sourceUnderTest) {
            if (targetGraph.get(buildTargetUnderTest) != null) {
              return true;
            }
          }

          return false;
        }
      };

      associatedTests = ImmutableSet.copyOf(
          Sets.union(
              ImmutableSet.copyOf(
                  projectGraph.getAll(explicitTests)),
              getAssociatedTargetNodes(
                  projectGraph,
                  projectRoots,
                  associatedTestsPredicate)
          )
      );
    }

    ImmutableSet<TargetNode<?>> associatedProjects = getAssociatedTargetNodes(
        projectGraph,
        Iterables.concat(projectRoots, associatedTests),
        associatedProjectPredicate);

    TargetGraph targetGraph = projectGraph.getSubgraph(
        Iterables.concat(projectRoots, associatedTests, associatedProjects));

    return new TargetGraphAndTargets(targetGraph, projectRoots, associatedTests);
  }

  /**
   * @param projectGraph A TargetGraph containing all nodes that could be related.
   * @param subgraphRoots Target nodes forming the roots of the subgraph to which the returned nodes
   *                      are related.
   * @param associatedTargetNodePredicate A predicate to determine whether a node is related or not.
   * @return A set of nodes related to {@code subgraphRoots} or their dependencies.
   */
  private static ImmutableSet<TargetNode<?>> getAssociatedTargetNodes(
      TargetGraph projectGraph,
      Iterable<TargetNode<?>> subgraphRoots,
      final AssociatedTargetNodePredicate associatedTargetNodePredicate) {
    final TargetGraph subgraph = projectGraph.getSubgraph(subgraphRoots);

    return FluentIterable
        .from(projectGraph.getNodes())
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> node) {
                return associatedTargetNodePredicate.apply(node, subgraph);
              }
            })
        .toSet();
  }
}
