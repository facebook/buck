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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Data structure used by Stampede for the distributable view of action graph. */
public class DistributableBuildGraph {
  private static final Logger LOG = Logger.get(DistributableBuildGraph.class);

  private final ImmutableMap<String, DistributableNode> allNodes;
  public final ImmutableSet<DistributableNode> leafNodes;

  public DistributableBuildGraph(
      ImmutableMap<String, DistributableNode> allNodes, ImmutableSet<DistributableNode> leafNodes) {
    this.allNodes = allNodes;
    this.leafNodes = leafNodes;
  }

  public DistributableNode getNode(String targetName) {
    return Preconditions.checkNotNull(
        allNodes.get(targetName), "Unknown node [%s] requested.", targetName);
  }

  public List<DistributableNode> getNodes(List<String> targets) {
    return targets.stream().map(this::getNode).collect(Collectors.toList());
  }

  public int size() {
    return allNodes.size();
  }

  public int getNumberOfCacheableNodes() {
    return Math.toIntExact(allNodes.values().stream().filter(t -> !t.isUncacheable()).count());
  }

  /** Custom structure for nodes used by the {@link BuildTargetsQueue}. */
  static class DistributableNode {
    private final String targetName;
    private final boolean uncacheable;
    public final ImmutableSet<String> dependentTargets;
    public final ImmutableSet<String> allDependencies;

    private final Set<String> dependenciesRemaining;
    private int unsatisfiedDependencies;
    private Optional<ImmutableSet<String>> transitiveCacheableDependents = Optional.empty();

    public DistributableNode(
        String targetName,
        ImmutableSet<String> dependentTargets,
        ImmutableSet<String> dependencyTargets,
        boolean uncacheable) {
      this.targetName = targetName;
      this.dependentTargets = dependentTargets;
      this.allDependencies = dependencyTargets;
      this.uncacheable = uncacheable;

      this.dependenciesRemaining = new HashSet<>(dependencyTargets);
      this.unsatisfiedDependencies = this.dependenciesRemaining.size();
    }

    public boolean isUncacheable() {
      return uncacheable;
    }

    public boolean areAllDependenciesResolved() {
      return 0 == unsatisfiedDependencies;
    }

    public int getNumUnsatisfiedDependencies() {
      return unsatisfiedDependencies;
    }

    public String getTargetName() {
      return targetName;
    }

    public boolean isDependencyRemaining(String targetName) {
      return dependenciesRemaining.contains(targetName);
    }

    public ImmutableSet<String> getTransitiveCacheableDependents(DistributableBuildGraph graph) {
      if (transitiveCacheableDependents.isPresent()) {
        return transitiveCacheableDependents.get();
      }

      ImmutableSet.Builder<String> cacheableDependents = ImmutableSet.builder();
      for (String parent : dependentTargets) {
        DistributableNode parentNode = graph.getNode(parent);
        if (parentNode.isUncacheable()) {
          cacheableDependents.addAll(parentNode.getTransitiveCacheableDependents(graph));
        } else {
          cacheableDependents.add(parent);
        }
      }

      transitiveCacheableDependents = Optional.of(cacheableDependents.build());
      return transitiveCacheableDependents.get();
    }

    public void finishDependency(String dependency) {
      if (!dependenciesRemaining.contains(dependency)) {
        boolean isActualDependency = allDependencies.contains(dependency);
        String errorMessage =
            String.format(
                "[%s] is not a remaining dependency of [%s]. Was it ever a real dependency? [%b].",
                dependency, targetName, isActualDependency);
        LOG.error(errorMessage);
        throw new RuntimeException(errorMessage);
      }

      dependenciesRemaining.remove(dependency);
      --unsatisfiedDependencies;

      if (LOG.isVerboseEnabled()) {
        LOG.verbose(
            String.format(
                ("Removing [%s] from remaining dependencies for target [%s],"
                    + " which now has [%d] unsatisfied dependencies."),
                dependency,
                targetName,
                unsatisfiedDependencies));
      }

      Preconditions.checkArgument(
          unsatisfiedDependencies >= 0,
          "The number of unsatisfied dependencies can never be negative.");
    }

    @Override
    public String toString() {
      return "DistributableNode{"
          + "targetName='"
          + targetName
          + '\''
          + ", unsatisfiedDependencies="
          + unsatisfiedDependencies
          + ", dependentTargets="
          + dependentTargets
          + '}';
    }
  }
}
