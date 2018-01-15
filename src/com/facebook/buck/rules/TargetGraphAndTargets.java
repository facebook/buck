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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Iterator;
import java.util.stream.Collectors;

public class TargetGraphAndTargets {
  private final TargetGraph targetGraph;
  private final ImmutableSet<TargetNode<?, ?>> projectRoots;

  private TargetGraphAndTargets(TargetGraph targetGraph, Iterable<TargetNode<?, ?>> projectRoots) {
    this.targetGraph = targetGraph;
    this.projectRoots = ImmutableSet.copyOf(projectRoots);
  }

  public TargetGraph getTargetGraph() {
    return targetGraph;
  }

  public ImmutableSet<TargetNode<?, ?>> getProjectRoots() {
    return projectRoots;
  }

  /**
   * @param nodes Nodes whose test targets we would like to find
   * @return A set of all test targets that test the targets in {@code nodes}.
   */
  public static ImmutableSet<BuildTarget> getExplicitTestTargets(Iterator<TargetNode<?, ?>> nodes) {
    return RichStream.from(nodes)
        .flatMap(node -> TargetNodes.getTestTargetsForNode(node).stream())
        .toImmutableSet();
  }

  public static TargetGraphAndTargets create(
      final ImmutableSet<BuildTarget> graphRoots,
      TargetGraph projectGraph,
      boolean isWithTests,
      ImmutableSet<BuildTarget> explicitTests) {
    // Get the roots of the main graph. This contains all the targets in the project slice, or all
    // the valid project roots if a project slice is not specified.
    Iterable<TargetNode<?, ?>> projectRoots = projectGraph.getAll(graphRoots);

    // Optionally get the roots of the test graph. This contains all the tests that cover the roots
    // of the main graph or their dependencies.
    Iterable<TargetNode<?, ?>> associatedTests = ImmutableSet.of();
    if (isWithTests) {
      associatedTests = projectGraph.getAll(explicitTests);
    }

    TargetGraph targetGraph =
        projectGraph.getSubgraph(Iterables.concat(projectRoots, associatedTests));

    return new TargetGraphAndTargets(targetGraph, projectRoots);
  }

  public static TargetGraphAndTargets toVersionedTargetGraphAndTargets(
      TargetGraphAndTargets targetGraphAndTargets,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      BuckEventBus buckEventBus,
      BuckConfig buckConfig,
      TypeCoercerFactory typeCoercerFactory,
      ImmutableSet<BuildTarget> explicitTestTargets)
      throws VersionException, InterruptedException {
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        TargetGraphAndBuildTargets.of(
            targetGraphAndTargets.getTargetGraph(),
            Sets.union(
                targetGraphAndTargets
                    .getProjectRoots()
                    .stream()
                    .map(root -> root.getBuildTarget())
                    .collect(Collectors.toSet()),
                explicitTestTargets));
    TargetGraphAndBuildTargets versionedTargetGraphAndBuildTargets =
        versionedTargetGraphCache.toVersionedTargetGraph(
            buckEventBus, buckConfig, typeCoercerFactory, targetGraphAndBuildTargets);
    return new TargetGraphAndTargets(
        versionedTargetGraphAndBuildTargets.getTargetGraph(),
        targetGraphAndTargets.getProjectRoots());
  }
}
