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
import com.facebook.buck.model.HasTests;
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
      ImmutableSet<TargetNode<?>> projectRoots,
      ImmutableSet<TargetNode<?>> associatedTests) {
    this.targetGraph = targetGraph;
    this.projectRoots = projectRoots;
    this.associatedTests = associatedTests;
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

  public static TargetGraphAndTargets create(
      ImmutableSet<BuildTarget> graphRoots,
      TargetGraph fullGraph,
      AssociatedTargetNodePredicate associatedProjectPredicate,
      boolean isWithTests) {
    // Get the roots of the main graph. This contains all the targets in the project slice, or all
    // the valid project roots if a project slice is not specified.
    ImmutableSet.Builder<TargetNode<?>> projectRootsBuilder = ImmutableSet.builder();
    for (BuildTarget target : graphRoots) {
      TargetNode<?> targetNode = fullGraph.get(target);
      if (targetNode == null) {
        throw new HumanReadableException("Target '%s' does not exist.", target);
      }
      projectRootsBuilder.add(targetNode);
    }
    ImmutableSet<TargetNode<?>> projectRoots = projectRootsBuilder.build();

    // Optionally get the roots of the test graph. This contains all the tests that cover the roots
    // of the main graph or their dependencies.
    ImmutableSet<TargetNode<?>> associatedTests = ImmutableSet.of();
    if (isWithTests) {
      ImmutableSet<BuildTarget> explicitTests = FluentIterable
          .from(fullGraph.getSubgraph(projectRoots).getNodes())
          .transformAndConcat(
              new Function<TargetNode<?>, Iterable<BuildTarget>>() {
                @Override
                public Iterable<BuildTarget> apply(TargetNode<?> node) {
                  if (node.getConstructorArg() instanceof HasTests) {
                    return ((HasTests) node.getConstructorArg()).getTests();
                  } else {
                    return ImmutableSet.of();
                  }
                }
              })
          .toSet();

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
                  fullGraph.getAll(explicitTests)),
              getAssociatedTargetNodes(
                  fullGraph,
                  projectRoots,
                  associatedTestsPredicate)
          )
      );
    }

    ImmutableSet<TargetNode<?>> associatedProjects = getAssociatedTargetNodes(
        fullGraph,
        Iterables.concat(projectRoots, associatedTests),
        associatedProjectPredicate);

    TargetGraph targetGraph = fullGraph.getSubgraph(
        Iterables.concat(projectRoots, associatedTests, associatedProjects));

    return new TargetGraphAndTargets(targetGraph, projectRoots, associatedTests);
  }

  /**
   * @param fullGraph A TargetGraph containing all nodes that could be related.
   * @param subgraphRoots Target nodes forming the roots of the subgraph to which the returned nodes
   *                      are related.
   * @param associatedTargetNodePredicate A predicate to determine whether a node is related or not.
   * @return A set of nodes related to {@code subgraphRoots} or their dependencies.
   */
  private static ImmutableSet<TargetNode<?>> getAssociatedTargetNodes(
      TargetGraph fullGraph,
      Iterable<TargetNode<?>> subgraphRoots,
      final AssociatedTargetNodePredicate associatedTargetNodePredicate) {
    final TargetGraph subgraph = fullGraph.getSubgraph(subgraphRoots);

    return FluentIterable
        .from(fullGraph.getNodes())
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
