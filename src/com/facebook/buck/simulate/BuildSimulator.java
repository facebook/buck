/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.simulate;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

public class BuildSimulator {
  private final SimulateTimes times;
  private final ActionGraph actionGraph;
  private final int numberOfThreads;

  public BuildSimulator(
      SimulateTimes times,
      ActionGraph actionGraph,
      int numberOfThreads) {
    this.times = times;
    this.actionGraph = actionGraph;
    this.numberOfThreads = numberOfThreads;
  }

  public SimulateReport simulateBuild(
      long currentTimeMillis,
      ImmutableList<BuildTarget> buildTargets) {
    Preconditions.checkArgument(buildTargets.size() > 0, "No targets provided for the simulation.");
    SimulateReport.Builder simulateReport = SimulateReport.builder();

    for (String timeAggregate : times.getTimeAggregates()) {
      SingleRunReport.Builder report = SingleRunReport.builder()
          .setTimestampMillis(currentTimeMillis)
          .setBuildTargets(FluentIterable.from(buildTargets)
              .transform(Functions.toStringFunction()))
          .setSimulateTimesFile(times.getFile())
          .setRuleFallbackTimeMillis(times.getRuleFallbackTimeMillis())
          .setTotalActionGraphNodes(Iterables.size(actionGraph.getNodes()))
          .setTimeAggregate(timeAggregate)
          .setNumberOfThreads(numberOfThreads);

      // Setup the build order.
      Map<BuildTarget, NodeState> reverseDependencies = Maps.newHashMap();
      Queue<BuildTarget> leafNodes = Queues.newArrayDeque();
      int totalDagEdges = 0;
      for (BuildTarget target : buildTargets) {
        BuildRule rule = Preconditions.checkNotNull(actionGraph.findBuildRuleByTarget(target));
        totalDagEdges += recursiveTraversal(rule, reverseDependencies, leafNodes);
      }
      report.setTotalDependencyDagEdges(totalDagEdges);

      // Run the simulation.
      simulateReport.addRunReports(
          runSimulation(report, reverseDependencies, leafNodes, timeAggregate));
    }

    return simulateReport.build();
  }

  private SingleRunReport runSimulation(
      SingleRunReport.Builder report,
      Map<BuildTarget, NodeState> reverseDependencies,
      Queue<BuildTarget> buildableNodes,
      String timeAggregate) {

    // Start simulation.
    long simulationCurrentMillis = 0;
    int nodesBuilt = 0;
    int targetsThatUsedTheFallbackTimeMillis = 0;
    PriorityQueue<RunningNode> targetsRunning = new PriorityQueue<>();
    while (nodesBuilt < reverseDependencies.size()) {

      // 1. Remove BuildTargets that are finished.
      while (!targetsRunning.isEmpty() &&
          targetsRunning.peek().getExpectedFinishMillis() <= simulationCurrentMillis) {
        BuildTarget target = targetsRunning.remove().getTarget();
        ++nodesBuilt;
        NodeState nodeState = Preconditions.checkNotNull(reverseDependencies.get(target));
        for (BuildTarget dependant : nodeState.getDependantNodes()) {
          NodeState dependantState = Preconditions.checkNotNull(reverseDependencies.get(dependant));
          dependantState.decrementRefCount();
          if (dependantState.canBuildNow()) {
            buildableNodes.add(dependant);
          }
        }
      }

      // 2. Re-enqueue BuildTargets that can now be ran.
      while (targetsRunning.size() < numberOfThreads &&
          buildableNodes.size() > 0) {
        BuildTarget target = buildableNodes.remove();
        long expectedFinishMillis = simulationCurrentMillis +
            times.getMillisForTarget(target.toString(), timeAggregate);
        targetsRunning.add(new RunningNode(expectedFinishMillis, target));

        if (!times.hasMillisForTarget(target.toString(), timeAggregate)) {
          ++targetsThatUsedTheFallbackTimeMillis;
        }
      }

      // 3. Compute the next cycle time.
      if (!targetsRunning.isEmpty()) {
        simulationCurrentMillis = targetsRunning.peek().getExpectedFinishMillis();
      }
    }

    report.setUsedActionGraphNodes(nodesBuilt)
        .setBuildDurationMillis(simulationCurrentMillis)
        .setActionGraphNodesWithoutSimulateTime(targetsThatUsedTheFallbackTimeMillis);
    return report.build();
  }

  /**
   *
   * @param rule
   * @param reverseDependencies
   * @param leafNodes
   * @return The number of DAG edges traversed.
   */
  private int recursiveTraversal(
      BuildRule rule,
      Map<BuildTarget, NodeState> reverseDependencies,
      Queue<BuildTarget> leafNodes) {
    int totalDagEdges = 0;
    BuildTarget target = rule.getBuildTarget();
    if (reverseDependencies.containsKey(target)) {
      return totalDagEdges;
    }

    // First collect all dependencies of the current BuildRule.
    List<BuildRule> deps = Lists.newArrayList();
    deps.addAll(rule.getDeps());
    if (rule instanceof HasRuntimeDeps) {
      deps.addAll(((HasRuntimeDeps) rule).getRuntimeDeps());
    }

    // Now recursively build the reverse dependencies.
    totalDagEdges = rule.getDeps().size();
    NodeState state = new NodeState();
    reverseDependencies.put(target, state);
    for (BuildRule dep : deps) {
      totalDagEdges += recursiveTraversal(dep, reverseDependencies, leafNodes);
      NodeState nodeState = Preconditions.checkNotNull(
          reverseDependencies.get(dep.getBuildTarget()));
      nodeState.addDependant(target);
      state.incrementRefCount();
    }

    // And now we are done with the current BuildRule, mark it for build if there's no dependencies.
    if (state.canBuildNow()) {
      leafNodes.add(target);
    }

    return totalDagEdges;
  }

  private static class NodeState {
    private final List<BuildTarget> dependantNodes;
    private int referenceCount;

    public List<BuildTarget> getDependantNodes() {
      return dependantNodes;
    }

    public boolean canBuildNow() {
      return 0 == referenceCount;
    }

    public NodeState() {
      this.dependantNodes = Lists.newArrayList();
      this.referenceCount = 0;
    }

    public void incrementRefCount() {
      ++referenceCount;
    }

    public void decrementRefCount() {
      Preconditions.checkState(
          referenceCount > 0,
          "Cannot decrement the referenceCount to a negative value.");
      --referenceCount;
    }

    public void addDependant(BuildTarget target) {
      dependantNodes.add(target);
    }
  }

  private static class RunningNode implements Comparable<RunningNode> {
    private final long expectedFinishMillis;
    private final BuildTarget target;

    public RunningNode(long expectedFinishMillis, BuildTarget target) {
      this.expectedFinishMillis = expectedFinishMillis;
      this.target = target;
    }

    public long getExpectedFinishMillis() {
      return expectedFinishMillis;
    }

    public BuildTarget getTarget() {
      return target;
    }

    @Override
    public int compareTo(RunningNode o) {
      if (this == o) {
        return 0;
      }

      return (int) (this.expectedFinishMillis - o.expectedFinishMillis);
    }
  }
}
