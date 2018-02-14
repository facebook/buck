/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.TimedLogger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

// NOTE: Not thread safe. Caller needs to synchronize access if using multiple threads.
public class BuildTargetsQueue {
  private static final TimedLogger LOG = new TimedLogger(Logger.get(BuildTargetsQueue.class));
  private List<EnqueuedTarget> zeroDependencyTargets;
  private final Map<String, EnqueuedTarget> allEnqueuedTargets;
  private final Set<String> seenFinishedNodes = new HashSet<>();
  private final Set<EnqueuedTarget> uncachableZeroDependencyTargets = new HashSet<>();
  private final int totalCacheableNodes;
  private int finishedCacheableNodes = 0;
  private final int mostBuildRulesFinishedPercentageThreshold;
  private int totalBuiltCount = 0;
  private int skippedUncacheablesCount = 0;

  BuildTargetsQueue(
      List<EnqueuedTarget> zeroDependencyTargets,
      Map<String, EnqueuedTarget> allEnqueuedTargets,
      Set<EnqueuedTarget> uncachableZeroDependencyTargets,
      int mostBuildRulesFinishedPercentageThreshold) {
    LOG.verbose(
        String.format(
            "Constructing queue with [%d] zero dependency targets and [%d] total targets.",
            zeroDependencyTargets.size(), allEnqueuedTargets.size()));
    this.zeroDependencyTargets = zeroDependencyTargets;
    this.allEnqueuedTargets = allEnqueuedTargets;
    this.uncachableZeroDependencyTargets.addAll(uncachableZeroDependencyTargets);
    this.mostBuildRulesFinishedPercentageThreshold = mostBuildRulesFinishedPercentageThreshold;
    completeUncachableZeroDependencyNodes();

    totalCacheableNodes =
        (int) allEnqueuedTargets.values().stream().filter(t -> !t.uncachable).count();
  }

  /** @return True if configured percentage of builds rules have finished. */
  public boolean haveMostBuildRulesFinished() {
    if (totalCacheableNodes == 0) {
      return true;
    }

    int percentageOfRulesFinished =
        (int) ((double) finishedCacheableNodes / totalCacheableNodes * 100);

    // Uncomment for debugging:
    //    LOG.info("Percentage of finished rules: " + percentageOfRulesFinished);

    return percentageOfRulesFinished >= mostBuildRulesFinishedPercentageThreshold;
  }

  public static BuildTargetsQueue newEmptyQueue() {
    return new BuildTargetsQueue(new ArrayList<>(), new HashMap<>(), new HashSet<>(), 0);
  }

  public boolean hasReadyZeroDependencyNodes() {
    return zeroDependencyTargets.size() > 0;
  }

  /** Returns nodes that have all their dependencies satisfied. */
  public List<WorkUnit> dequeueZeroDependencyNodes(List<String> finishedNodes, int maxUnitsOfWork) {
    Preconditions.checkArgument(maxUnitsOfWork >= 0);

    LOG.info(
        String.format(
            "Received update with [%s] finished nodes and [%s] requested work units",
            finishedNodes.size(), maxUnitsOfWork));

    processFinishedNodes(finishedNodes);

    if (maxUnitsOfWork == 0) {
      return Lists.newArrayList();
    }

    List<WorkUnit> newUnitsOfWork = new ArrayList<>();
    createWorkUnitsStartingAtNodes(
        new HashSet<>(zeroDependencyTargets), newUnitsOfWork, maxUnitsOfWork);

    if (newUnitsOfWork.size() > 0) {
      LOG.info(String.format("Returning [%s] work units", newUnitsOfWork.size()));
    }

    return Lists.newArrayList(newUnitsOfWork);
  }

  private void completeUncachableZeroDependencyNodes() {
    while (uncachableZeroDependencyTargets.size() > 0) {
      EnqueuedTarget target = uncachableZeroDependencyTargets.iterator().next();
      LOG.debug(
          String.format(
              "Automatically marking uncachable zero dependency node [%s] as completed.",
              target.buildTarget));
      skippedUncacheablesCount++;
      processFinishedNode(target);
      zeroDependencyTargets.remove(target);
      uncachableZeroDependencyTargets.remove(target);
    }
  }

  private void processFinishedNodes(List<String> finishedNodes) {
    totalBuiltCount += finishedNodes.size();

    for (String node : finishedNodes) {
      EnqueuedTarget target = Preconditions.checkNotNull(allEnqueuedTargets.get(node));
      processFinishedNode(target);
    }

    completeUncachableZeroDependencyNodes();

    LOG.info(
        String.format(
            "Queue Status: Zero dependency nodes [%s]. Total nodes [%s]. Built [%s]",
            zeroDependencyTargets.size(), allEnqueuedTargets.size(), totalBuiltCount));
  }

  /** Method to publish build progress. */
  public CoordinatorBuildProgress getBuildProgress() {
    return new CoordinatorBuildProgress()
        .setTotalRulesCount(allEnqueuedTargets.size())
        .setBuiltRulesCount(totalBuiltCount)
        .setSkippedRulesCount(skippedUncacheablesCount);
  }

  private void processFinishedNode(EnqueuedTarget target) {
    if (seenFinishedNodes.contains(target.buildTarget)) {
      String errorMessage = String.format("[%s] has already finished once", target.buildTarget);
      LOG.error(errorMessage);
      throw new RuntimeException(errorMessage);
    }
    seenFinishedNodes.add(target.buildTarget);
    finishedCacheableNodes += (target.uncachable ? 0 : 1);

    ImmutableList<String> dependents = target.getDependentTargets();
    LOG.debug(
        String.format(
            "Complete node [%s] has [%s] dependents", target.buildTarget, dependents.size()));
    for (String dependent : dependents) {
      EnqueuedTarget dep = Preconditions.checkNotNull(allEnqueuedTargets.get(dependent));
      dep.decrementUnsatisfiedDeps(target.buildTarget);

      if (dep.areAllDependenciesResolved() && !dep.uncachable && !dep.partOfBuildingUnitOfWork) {
        zeroDependencyTargets.add(dep);
      } else if (dep.areAllDependenciesResolved() && dep.uncachable) {
        LOG.debug(
            String.format(
                "Uncachable dependent [%s] is ready to auto complete.", dep.getBuildTarget()));
        zeroDependencyTargets.add(dep);
        uncachableZeroDependencyTargets.add(dep);
      } else if (dep.areAllDependenciesResolved()) {
        // If a child node made a parent node ready to build, but that parent node is already
        // part of a work unit, then no need to add it to zero dependency list (it is already
        // being build by the minion that just completed the child).
        LOG.debug(
            String.format(
                "Dependent [%s] is ready, but already build as part of work unit.",
                dep.getBuildTarget()));
      }
    }
  }

  private void createWorkUnitsStartingAtNodes(
      Set<EnqueuedTarget> nodes, List<WorkUnit> newUnitsOfWork, int maxUnitsOfWork) {
    for (EnqueuedTarget node : nodes) {
      if (newUnitsOfWork.size() >= maxUnitsOfWork) {
        return;
      }

      if (node.partOfBuildingUnitOfWork) {
        continue; // Node may form work unit with an earlier zero dependency node
      }

      LOG.debug(
          String.format(
              "Node [%s] is zero dependency. Starting unit of work from here",
              node.getBuildTarget()));

      newUnitsOfWork.add(getUnitOfWorkStartingAtLeafNode(node));
    }
  }

  private void addToWorkUnit(
      EnqueuedTarget node, List<String> unitOfWork, Queue<EnqueuedTarget> nodesToCheck) {
    if (node.isUncachable()) {
      // Uncachables do not need to be scheduled explicitly. If they are needed for a
      // cachable in the chain, they will be built anyway.
      // Note: we still need to check the parent of this uncachable, as it might be cacheable.
      LOG.debug(
          String.format("Skipping adding uncachable [%s] to work unit.", node.getBuildTarget()));
    } else {
      LOG.debug(String.format("Adding [%s] to work unit.", node.getBuildTarget()));
      node.partOfBuildingUnitOfWork = true;
      unitOfWork.add(node.getBuildTarget()); // Reverse dependency order
    }

    nodesToCheck.add(node);
    zeroDependencyTargets.remove(node);
  }

  private WorkUnit getUnitOfWorkStartingAtLeafNode(EnqueuedTarget leafNode) {
    if (leafNode.partOfBuildingUnitOfWork) {
      throw new RuntimeException(
          String.format(
              "Leaf node [%s] is already part of a work unit", leafNode.getBuildTarget()));
    }

    List<String> workUnitNodes = new LinkedList<>(); //
    Queue<EnqueuedTarget> nodesToCheck = new LinkedList<>();
    addToWorkUnit(leafNode, workUnitNodes, nodesToCheck);

    while (nodesToCheck.size() == 1) {
      EnqueuedTarget currentNode = nodesToCheck.remove();
      // If a node has more than one parent, then it should be the last node in the chain.
      if (currentNode.dependentTargets.size() != 1) {
        break;
      }

      // If a node has a single parent, but that parent has multiple children and some of them
      // are not finished yet, stop at the current node.
      EnqueuedTarget parent =
          Preconditions.checkNotNull(allEnqueuedTargets.get(currentNode.dependentTargets.get(0)));
      if (parent.unsatisfiedDependencies != 1 || parent.partOfBuildingUnitOfWork) {
        break;
      }

      addToWorkUnit(parent, workUnitNodes, nodesToCheck);
    }

    WorkUnit workUnit = new WorkUnit();
    workUnit.setBuildTargets(workUnitNodes);

    return workUnit;
  }

  /** Custom structure for nodes used by the {@link BuildTargetsQueue}. */
  static class EnqueuedTarget {
    private final String buildTarget;
    private final ImmutableList<String> dependentTargets;
    private final Set<String> allDependencies;
    private final Set<String> dependenciesRemaining;
    private int unsatisfiedDependencies;
    private boolean uncachable;

    private boolean partOfBuildingUnitOfWork = false;

    public EnqueuedTarget(
        String buildTarget,
        ImmutableList<String> dependentTargets,
        int numberOfDependencies,
        ImmutableSet<String> dependenciesRemaining,
        boolean uncachable) {
      this.buildTarget = buildTarget;
      this.dependentTargets = dependentTargets;
      this.unsatisfiedDependencies = numberOfDependencies;
      this.dependenciesRemaining = new HashSet<>(dependenciesRemaining);
      this.allDependencies = new HashSet<>(dependenciesRemaining);
      this.uncachable = uncachable;
    }

    public boolean isUncachable() {
      return uncachable;
    }

    public boolean areAllDependenciesResolved() {
      return 0 == unsatisfiedDependencies;
    }

    public String getBuildTarget() {
      return buildTarget;
    }

    public ImmutableList<String> getDependentTargets() {
      return dependentTargets;
    }

    public void decrementUnsatisfiedDeps(String dependency) {
      if (!dependenciesRemaining.contains(dependency)) {
        boolean isActualDependency = allDependencies.contains(dependency);
        String errorMessage =
            String.format(
                "[%s] is not a remaining dependency of [%s]. Is real dependency [%s]",
                dependency, buildTarget, isActualDependency);
        LOG.error(errorMessage);
        throw new RuntimeException(errorMessage);
      }

      if (LOG.isVerboseEnabled()) {
        LOG.verbose(
            String.format(
                ("Removing [%s] from remaining dependencies for target [%s],"
                    + " which now has [%s] unsatisfied dependencies."),
                dependency,
                buildTarget,
                unsatisfiedDependencies));
      }

      dependenciesRemaining.remove(dependency);
      --unsatisfiedDependencies;
      Preconditions.checkArgument(
          unsatisfiedDependencies >= 0,
          "The number of unsatisfied dependencies can never be negative.");
    }

    @Override
    public String toString() {
      return "EnqueuedTarget{"
          + "buildTarget='"
          + buildTarget
          + '\''
          + ", unsatisfiedDependencies="
          + unsatisfiedDependencies
          + ", dependentTargets="
          + dependentTargets
          + '}';
    }
  }
}
