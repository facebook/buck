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

import com.facebook.buck.distributed.build_slave.DistributableBuildGraph.DistributableNode;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.TimedLogger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

// NOTE: Not thread safe. Caller needs to synchronize access if using multiple threads.
public class BuildTargetsQueue {
  private static final TimedLogger LOG = new TimedLogger(Logger.get(BuildTargetsQueue.class));
  private final DistributableBuildGraph distributableBuildGraph;
  private final int totalCacheableNodes;
  private final int mostBuildRulesFinishedPercentageThreshold;

  private final Set<String> seenWorkingNodes = new HashSet<>();
  private final Set<String> seenFinishedNodes = new HashSet<>();
  private final List<String> zeroDependencyTargets;
  private final Set<String> uncachableZeroDependencyTargets;
  private int finishedCacheableNodes = 0;
  private int totalBuiltCount = 0;
  private int skippedUncacheablesCount = 0;

  BuildTargetsQueue(
      DistributableBuildGraph distributableBuildGraph,
      int mostBuildRulesFinishedPercentageThreshold) {
    this.distributableBuildGraph = distributableBuildGraph;
    this.mostBuildRulesFinishedPercentageThreshold = mostBuildRulesFinishedPercentageThreshold;

    this.zeroDependencyTargets =
        distributableBuildGraph
            .leafNodes
            .stream()
            .map(DistributableNode::getTargetName)
            .collect(Collectors.toList());
    this.uncachableZeroDependencyTargets =
        distributableBuildGraph
            .leafNodes
            .stream()
            .filter(DistributableNode::isUncachable)
            .map(DistributableNode::getTargetName)
            .collect(Collectors.toSet());
    totalCacheableNodes = distributableBuildGraph.getNumCachableNodes();

    LOG.verbose(
        String.format(
            "Constructing queue with [%d] zero dependency targets and [%d] total targets.",
            zeroDependencyTargets.size(), distributableBuildGraph.size()));

    completeUncachableZeroDependencyNodes();
  }

  public DistributableBuildGraph getDistributableBuildGraph() {
    return distributableBuildGraph;
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
    return new BuildTargetsQueue(
        new DistributableBuildGraph(ImmutableMap.of(), ImmutableSet.of()), 0);
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
      String targetName = uncachableZeroDependencyTargets.iterator().next();
      LOG.debug(
          String.format(
              "Automatically marking uncachable zero dependency node [%s] as completed.",
              targetName));
      skippedUncacheablesCount++;
      processFinishedNode(distributableBuildGraph.getNode(targetName));
      zeroDependencyTargets.remove(targetName);
      uncachableZeroDependencyTargets.remove(targetName);
    }
  }

  private void processFinishedNodes(List<String> finishedNodes) {
    totalBuiltCount += finishedNodes.size();

    for (String node : finishedNodes) {
      DistributableNode target = Preconditions.checkNotNull(distributableBuildGraph.getNode(node));
      processFinishedNode(target);
    }

    completeUncachableZeroDependencyNodes();

    LOG.info(
        String.format(
            "Queue Status: Zero dependency nodes [%s]. Total nodes [%s]. Built [%s]",
            zeroDependencyTargets.size(), distributableBuildGraph.size(), totalBuiltCount));
  }

  /** Method to publish build progress. */
  public CoordinatorBuildProgress getBuildProgress() {
    return new CoordinatorBuildProgress()
        .setTotalRulesCount(distributableBuildGraph.size())
        .setBuiltRulesCount(totalBuiltCount)
        .setSkippedRulesCount(skippedUncacheablesCount);
  }

  private void processFinishedNode(DistributableNode target) {
    if (seenFinishedNodes.contains(target.getTargetName())) {
      String errorMessage = String.format("[%s] has already finished once", target.getTargetName());
      LOG.error(errorMessage);
      throw new RuntimeException(errorMessage);
    }
    seenFinishedNodes.add(target.getTargetName());
    finishedCacheableNodes += (target.isUncachable() ? 0 : 1);

    ImmutableSet<String> dependents = target.dependentTargets;
    LOG.debug(
        String.format(
            "Complete node [%s] has [%s] dependents", target.getTargetName(), dependents.size()));
    for (String dependent : dependents) {
      DistributableNode dep =
          Preconditions.checkNotNull(distributableBuildGraph.getNode(dependent));
      dep.finishDependency(target.getTargetName());

      if (dep.areAllDependenciesResolved()
          && !dep.isUncachable()
          && !seenWorkingNodes.contains(dep.getTargetName())) {
        zeroDependencyTargets.add(dep.getTargetName());
      } else if (dep.areAllDependenciesResolved() && dep.isUncachable()) {
        LOG.debug(
            String.format(
                "Uncachable dependent [%s] is ready to auto complete.", dep.getTargetName()));
        zeroDependencyTargets.add(dep.getTargetName());
        uncachableZeroDependencyTargets.add(dep.getTargetName());
      } else if (dep.areAllDependenciesResolved()) {
        // If a child node made a parent node ready to build, but that parent node is already
        // part of a work unit, then no need to add it to zero dependency list (it is already
        // being build by the minion that just completed the child).
        LOG.debug(
            String.format(
                "Dependent [%s] is ready, but already build as part of work unit.",
                dep.getTargetName()));
      }
    }
  }

  private void createWorkUnitsStartingAtNodes(
      Set<String> nodes, List<WorkUnit> newUnitsOfWork, int maxUnitsOfWork) {
    for (String node : nodes) {
      if (newUnitsOfWork.size() >= maxUnitsOfWork) {
        return;
      }

      if (seenWorkingNodes.contains(node)) {
        continue; // Node may form work unit with an earlier zero dependency node
      }

      LOG.debug(
          String.format("Node [%s] is zero dependency. Starting unit of work from here", node));

      newUnitsOfWork.add(getUnitOfWorkStartingAtLeafNode(node));
    }
  }

  private void addToWorkUnit(
      DistributableNode node, List<String> unitOfWork, Queue<DistributableNode> nodesToCheck) {
    if (node.isUncachable()) {
      // Uncachables do not need to be scheduled explicitly. If they are needed for a
      // cachable in the chain, they will be built anyway.
      // Note: we still need to check the parent of this uncachable, as it might be cacheable.
      LOG.debug(
          String.format("Skipping adding uncachable [%s] to work unit.", node.getTargetName()));
    } else {
      LOG.debug(String.format("Adding [%s] to work unit.", node.getTargetName()));
      seenWorkingNodes.add(node.getTargetName());
      unitOfWork.add(node.getTargetName()); // Reverse dependency order
    }

    nodesToCheck.add(node);
    zeroDependencyTargets.remove(node.getTargetName());
  }

  private WorkUnit getUnitOfWorkStartingAtLeafNode(String leafNode) {
    if (seenWorkingNodes.contains(leafNode)) {
      throw new RuntimeException(
          String.format("Leaf node [%s] is already part of a work unit", leafNode));
    }

    List<String> workUnitNodes = new LinkedList<>(); //
    Queue<DistributableNode> nodesToCheck = new LinkedList<>();
    addToWorkUnit(distributableBuildGraph.getNode(leafNode), workUnitNodes, nodesToCheck);

    while (nodesToCheck.size() == 1) {
      DistributableNode currentNode = nodesToCheck.remove();
      // If a node has more than one parent, then it should be the last node in the chain.
      if (currentNode.dependentTargets.size() != 1) {
        break;
      }

      // If a node has a single parent, but that parent has multiple children and some of them
      // are not finished yet, stop at the current node.
      DistributableNode parent =
          Preconditions.checkNotNull(
              distributableBuildGraph.getNode(currentNode.dependentTargets.asList().get(0)));
      if (parent.getNumUnsatisfiedDependencies() != 1
          || seenWorkingNodes.contains(parent.getTargetName())) {
        break;
      }

      addToWorkUnit(parent, workUnitNodes, nodesToCheck);
    }

    WorkUnit workUnit = new WorkUnit();
    workUnit.setBuildTargets(workUnitNodes);

    return workUnit;
  }
}
