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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.util.HumanReadableException;
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
import java.util.Queue;

public class BuildSimulator {
  private final SimulateTimes times;
  private final ActionGraph actionGraph;
  private final BuildRuleResolver resolver;
  private final int numberOfThreads;
  private final BuckEventBus eventBus;

  public BuildSimulator(
      BuckEventBus eventBus,
      SimulateTimes times,
      ActionGraph actionGraph,
      BuildRuleResolver resolver,
      int numberOfThreads) {
    this.eventBus = eventBus;
    this.times = times;
    this.actionGraph = actionGraph;
    this.resolver = resolver;
    this.numberOfThreads = numberOfThreads;
  }

  public SimulateReport simulateBuild(
      long currentTimeMillis,
      ImmutableList<BuildTarget> buildTargets) {
    Preconditions.checkArgument(buildTargets.size() > 0, "No targets provided for the simulation.");
    SimulateReport.Builder simulateReport = SimulateReport.builder();

    for (String timeAggregate : times.getTimeAggregates()) {
      // Setup the build order.
      Map<BuildTarget, NodeState> reverseDependencies = Maps.newHashMap();
      Queue<BuildTarget> leafNodes = Queues.newArrayDeque();
      int totalDagEdges = 0;
      for (BuildTarget target : buildTargets) {
        BuildRule rule;
        try {
          rule = resolver.requireRule(target);
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e.getHumanReadableErrorMessage());
        }

        totalDagEdges += recursiveTraversal(rule, reverseDependencies, leafNodes);
      }

      SingleRunReport.Builder report = SingleRunReport.builder()
          .setTimestampMillis(currentTimeMillis)
          .setBuildTargets(FluentIterable.from(buildTargets)
              .transform(Functions.toStringFunction()))
          .setSimulateTimesFile(times.getFile())
          .setRuleFallbackTimeMillis(times.getRuleFallbackTimeMillis())
          .setTotalActionGraphNodes(Iterables.size(actionGraph.getNodes()))
          .setTimeAggregate(timeAggregate)
          .setNumberOfThreads(numberOfThreads);

      report.setTotalDependencyDagEdges(totalDagEdges);

      // Run the simulation.
      simulateReport.addRunReports(
          runSimulation(currentTimeMillis, report, reverseDependencies, leafNodes, timeAggregate));
    }

    return simulateReport.build();
  }

  private SingleRunReport runSimulation(
      long currentMillis,
      SingleRunReport.Builder report,
      Map<BuildTarget, NodeState> reverseDependencies,
      Queue<BuildTarget> buildableNodes,
      String timeAggregate) {

    // Start simulation.
    int nodesBuilt = 0;
    int targetsThatUsedTheFallbackTimeMillis = 0;
    FakeThreadPool threadPool = new FakeThreadPool(numberOfThreads);
    while (nodesBuilt < reverseDependencies.size()) {

      // 1. Remove BuildTargets that are finished.
      for (SimulationNode node : threadPool.popFinishedNodes(currentMillis)) {
        SimulateEvent.Finished finished = new SimulateEvent.Finished(node, currentMillis);
        eventBus.post(finished);
        ++nodesBuilt;
        NodeState nodeState = Preconditions.checkNotNull(reverseDependencies.get(node.getTarget()));
        for (BuildTarget dependant : nodeState.getDependantNodes()) {
          NodeState dependantState = Preconditions.checkNotNull(reverseDependencies.get(dependant));
          dependantState.decrementRefCount();
          if (dependantState.canBuildNow()) {
            buildableNodes.add(dependant);
          }
        }
      }

      // 2. Re-enqueue BuildTargets that can now be ran.
      while (threadPool.hasAvailableThreads() && buildableNodes.size() > 0) {
        BuildTarget target = buildableNodes.remove();
        long expectedFinishMillis = currentMillis +
            times.getMillisForTarget(target.toString(), timeAggregate);
        SimulationNode node = threadPool.runTarget(expectedFinishMillis, target);
        SimulateEvent.Started started = new SimulateEvent.Started(node, currentMillis);
        eventBus.post(started);

        if (!times.hasMillisForTarget(target.toString(), timeAggregate)) {
          ++targetsThatUsedTheFallbackTimeMillis;
        }
      }

      // 3. Compute the next cycle time.
      if (threadPool.hasTargetsRunning()) {
        currentMillis = threadPool.getNextFinishingTargetMillis();
      }
    }

    report.setUsedActionGraphNodes(nodesBuilt)
        .setBuildDurationMillis(currentMillis)
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

  public static class SimulationNode implements Comparable<SimulationNode> {
    private final long expectedFinishMillis;
    private final BuildTarget target;
    private final int threadId;

    public SimulationNode(long expectedFinishMillis, BuildTarget target, int threadId) {
      this.expectedFinishMillis = expectedFinishMillis;
      this.target = target;
      this.threadId = threadId;
    }

    public long getExpectedFinishMillis() {
      return expectedFinishMillis;
    }

    public BuildTarget getTarget() {
      return target;
    }

    @Override
    public int compareTo(SimulationNode o) {
      if (this == o) {
        return 0;
      }

      return (int) (this.expectedFinishMillis - o.expectedFinishMillis);
    }

    public int getThreadId() {
      return threadId;
    }
  }

  private static class FakeThreadPool {
    private final SimulationNode[] runningNodes;
    private int runningNodesCount;

    public FakeThreadPool(int numberOfThreads) {
      this.runningNodes = new SimulationNode[numberOfThreads];
      this.runningNodesCount = 0;
    }

    public List<SimulationNode> popFinishedNodes(long simulationCurrentMillis) {
      List<SimulationNode> finishedNodes = Lists.newArrayList();
      for (int i = 0; i < runningNodes.length; ++i) {
        SimulationNode node = runningNodes[i];
        if (node == null) {
          continue;
        }

        if (node.getExpectedFinishMillis() <= simulationCurrentMillis) {
          runningNodes[i] = null;
          --runningNodesCount;
          finishedNodes.add(node);
        }
      }

      return finishedNodes;
    }

    public boolean hasAvailableThreads() {
      return runningNodesCount < runningNodes.length;
    }

    public SimulationNode runTarget(long expectedFinishMillis, BuildTarget target) {
      for (int i = 0; i < runningNodes.length; ++i) {
        if (runningNodes[i] == null) {
          SimulationNode node = new SimulationNode(expectedFinishMillis, target, i);
          runningNodes[i] = node;
          ++runningNodesCount;
          return node;
        }
      }

      throw new IllegalStateException("Tried to schedule a task without any available threads.");
    }

    public boolean hasTargetsRunning() {
      return runningNodesCount > 0;
    }

    public long getNextFinishingTargetMillis() {
      long finishMillis = Long.MAX_VALUE;
      for (int i = 0; i < runningNodes.length; ++i) {
        if (runningNodes[i] != null) {
          finishMillis = Math.min(finishMillis, runningNodes[i].getExpectedFinishMillis());
        }
      }

      return finishMillis;
    }
  }
}
