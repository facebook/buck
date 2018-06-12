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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Listens {@link ActionGraphEvent.Finished} to get {@link ActionGraph}, {@link BuildRuleEvent} to
 * trace {@link BuildRule} to compute critical path for test targets.
 */
public class BuildTargetDurationListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(BuildTargetDurationListener.class);

  private static final byte[] NEWLINE = "\n".getBytes(Charsets.UTF_8);
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 60;

  private final InvocationInfo info;
  private final ExecutorService executor;
  private final ProjectFilesystem filesystem;

  private Optional<ActionGraph> actionGraph = Optional.empty();
  // Hold the latest one for the target
  private ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = Maps.newConcurrentMap();

  public BuildTargetDurationListener(
      InvocationInfo info, ProjectFilesystem filesystem, ExecutorService executor) {
    this.info = info;
    this.filesystem = filesystem;
    this.executor = executor;
  }

  /** Save start of the {@link BuildRuleEvent}. */
  @Subscribe
  public void buildRuleEventStarted(BuildRuleEvent.Started event) {
    final String buildRuleName = event.getBuildRule().getFullyQualifiedName();
    final long startEpochMillis = event.getTimestamp();
    Preconditions.checkState(
        !buildRuleInfos.containsKey(buildRuleName),
        "There can not be more than one BuildRuleEvent.Started for the %s BuildRule.",
        buildRuleName);
    LOG.info("BuildRuleEventStarted{name=%s, time=%d}", buildRuleName, startEpochMillis);
    BuildRuleInfo ruleInfo = new BuildRuleInfo(buildRuleName, startEpochMillis, startEpochMillis);
    buildRuleInfos.put(buildRuleName, ruleInfo);
    event
        .getBuildRule()
        .getBuildDeps()
        .stream()
        .map(BuildRule::getFullyQualifiedName)
        .forEach(ruleInfo::addDependency);
  }

  /**
   * Save end of the {@link BuildRuleEvent} for {@link
   * com.facebook.buck.event.RuleKeyCalculationEvent}.
   */
  @Subscribe
  public synchronized void buildRuleEventSuspended(BuildRuleEvent.Suspended event) {
    LOG.info(
        "BuildRuleEventSuspended{name=%s, time=%d}",
        event.getBuildRule().getFullyQualifiedName(), event.getTimestamp());
    saveEndOfBuildRuleEvent(
        event.getBuildRule().getFullyQualifiedName(),
        event.getTimestamp(),
        event.getDuration().getWallMillisDuration());
  }

  /** Save end of the {@link BuildRuleEvent}. */
  @Subscribe
  public synchronized void buildRuleEventFinished(BuildRuleEvent.Finished event) {
    LOG.info(
        "BuildRuleEventFinished{name=%s, time=%d}",
        event.getBuildRule().getFullyQualifiedName(), event.getTimestamp());
    saveEndOfBuildRuleEvent(
        event.getBuildRule().getFullyQualifiedName(),
        event.getTimestamp(),
        event.getDuration().getWallMillisDuration());
  }

  /** Update end of the {@link BuildRuleEvent}. */
  private void saveEndOfBuildRuleEvent(String buildRuleName, long finishTimeMillis, long duration) {
    Preconditions.checkState(
        buildRuleInfos.containsKey(buildRuleName),
        "First, a BuildRuleEvent %s has to start in order to finish.",
        buildRuleName);
    BuildRuleInfo currentBuildRuleInfo = buildRuleInfos.get(buildRuleName);
    currentBuildRuleInfo.updateDuration(duration);
    currentBuildRuleInfo.updateFinish(finishTimeMillis);
  }

  /** Save the {@link ActionGraph} */
  @Subscribe
  public synchronized void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraph = finished.getActionGraph();
  }

  private Path getLogFilePath() {
    return filesystem.resolve(info.getLogDirectoryPath()).resolve("critical-path");
  }

  @Override
  public synchronized void outputTrace(BuildId buildId) {
    SimpleTimeLimiter timeLimiter = SimpleTimeLimiter.create(executor);
    try {
      timeLimiter.runWithTimeout(
          () -> {
            try (BufferedOutputStream outputStream =
                new BufferedOutputStream(new FileOutputStream(getLogFilePath().toFile()))) {
              LOG.info("Starting critical path calculation for %s", info);
              LOG.info("Writing critical path to %s", getLogFilePath().toString());
              if (actionGraph.isPresent()) {
                updateBuildRuleInfosWithDependents();
                Optional<BuildRuleInfo> criticalPathLast =
                    computeCriticalPathUsingGraphTraversal(buildRuleInfos);
                OptionalLong firstStartEpochMillis = OptionalLong.empty();
                Stack<BuildRuleInfo> criticalPath = stackOfCriticalPath(criticalPathLast);
                while (!criticalPath.empty()) {
                  BuildRuleInfo buildRuleInfo = criticalPath.pop();
                  if (!firstStartEpochMillis.isPresent()) {
                    firstStartEpochMillis = OptionalLong.of(buildRuleInfo.startEpochMillis);
                  }
                  outputStream.write(buildRuleInfo.ruleName.getBytes(Charsets.UTF_8));
                  outputStream.write(
                      (" start="
                              + (buildRuleInfo.startEpochMillis
                                  - firstStartEpochMillis.getAsLong()))
                          .getBytes(Charsets.UTF_8));
                  outputStream.write(
                      (" finish="
                              + (buildRuleInfo.finishEpochMillis
                                  - firstStartEpochMillis.getAsLong()))
                          .getBytes(Charsets.UTF_8));
                  outputStream.write(
                      (" all=" + buildRuleInfo.longestDependencyChainMillis)
                          .getBytes(Charsets.UTF_8));
                  outputStream.write(NEWLINE);
                }

                outputStream.write(
                    "\n====== TEST TARGET DURATIONS =======\n".getBytes(Charsets.UTF_8));
                Iterable<TestRule> testRules =
                    Iterables.filter(actionGraph.get().getNodes(), TestRule.class);
                for (TestRule testRule : testRules) {
                  BuildRuleInfo ruleInfo = buildRuleInfos.get(testRule.getFullyQualifiedName());
                  outputStream.write(testRule.getFullyQualifiedName().getBytes(Charsets.UTF_8));
                  if (ruleInfo != null) {
                    outputStream.write(
                        (" getDuration=" + ruleInfo.getWholeTargetDuration())
                            .getBytes(Charsets.UTF_8));
                  }
                  outputStream.write(NEWLINE);
                }
                LOG.info("Critical path written successfully.");
              } else {
                outputStream.write("NO ACTION GRAPH\n".getBytes(Charsets.UTF_8));
                LOG.warn("There was no action graph, computation is skipped.");
              }

            } catch (IOException e) {
              LOG.warn(e, "Could not complete IO Request");
            }
          },
          SHUTDOWN_TIMEOUT_SECONDS,
          TimeUnit.SECONDS);
    } catch (InterruptedException | TimeoutException e) {
      LOG.error(e, "Could not complete all jobs within timeout during shutdown");
    } catch (AssertionError e) {
      LOG.error(e.getCause(), "An exception occurred during execution of task");
    } finally {
      executor.shutdownNow();
    }
  }

  private void updateBuildRuleInfosWithDependents() {
    if (!actionGraph.isPresent()) {
      return;
    }
    for (BuildRule buildRule : actionGraph.get().getNodes()) {
      // for each dependency include a back-edge from dependency to dependent
      buildRule
          .getBuildDeps()
          .forEach(
              dependency -> {
                BuildRuleInfo dependencyBuildRuleInfo =
                    buildRuleInfos.get(dependency.getFullyQualifiedName());
                // It might not exist if a BuildRuleEvent is not executed!
                if (dependencyBuildRuleInfo != null) {
                  dependencyBuildRuleInfo.addDependent(buildRule.getFullyQualifiedName());
                }
              });
    }
  }

  /** Compute critical path using {@link TraversableGraph} interface. */
  @VisibleForTesting
  static Optional<BuildRuleInfo> computeCriticalPathUsingGraphTraversal(
      Map<String, BuildRuleInfo> buildRuleInfos) throws IOException {
    BuildRuleInfoGraph graph = new BuildRuleInfoGraph(buildRuleInfos);
    new BuildRuleInfoGraphTraversal(graph).traverse();
    return findCriticalNode(graph.getBuildRuleInfos());
  }

  private static Optional<BuildRuleInfo> findCriticalNode(Iterable<BuildRuleInfo> allRules) {
    Optional<BuildRuleInfo> lastNodeOfCriticalPath = Optional.empty();
    long timeOfLastNodeOfCriticalPath = 0;
    for (BuildRuleInfo buildRuleInfo : allRules) {
      // Update longest chain seen so far
      if (-timeOfLastNodeOfCriticalPath > -(buildRuleInfo.longestDependencyChainMillis)) {
        timeOfLastNodeOfCriticalPath = buildRuleInfo.longestDependencyChainMillis;
        lastNodeOfCriticalPath = Optional.of(buildRuleInfo);
      }
    }
    return lastNodeOfCriticalPath;
  }

  /** Construct critical path. */
  @VisibleForTesting
  static Stack<BuildRuleInfo> stackOfCriticalPath(
      Optional<BuildRuleInfo> lastCriticalPathBuildRuleInfo) {
    Stack<BuildRuleInfo> criticalPath = new Stack<>();
    while (lastCriticalPathBuildRuleInfo.isPresent()) {
      criticalPath.push(lastCriticalPathBuildRuleInfo.get());
      lastCriticalPathBuildRuleInfo =
          lastCriticalPathBuildRuleInfo.get().previousRuleInLongestDependencyChain;
    }
    return criticalPath;
  }

  /** Class extending {@link AbstractBottomUpTraversal} to do traversal bottom up. */
  public static class BuildRuleInfoGraphTraversal
      extends AbstractBottomUpTraversal<BuildRuleInfo, IOException> {

    private BuildRuleInfoGraph graph;

    public BuildRuleInfoGraphTraversal(BuildRuleInfoGraph graph) {
      super(graph);
      this.graph = graph;
    }

    @Override
    public void visit(BuildRuleInfo currentBuildRuleInfo) {
      Optional<BuildRuleInfo> longestDependencyChain = Optional.empty();
      long longestDependencyChainMillis = 0;
      for (BuildRuleInfo neighborBuildRuleInfo : graph.getOutgoingNodesFor(currentBuildRuleInfo)) {
        if (neighborBuildRuleInfo.longestDependencyChainMillis > longestDependencyChainMillis) {
          longestDependencyChainMillis = neighborBuildRuleInfo.longestDependencyChainMillis;
          longestDependencyChain = Optional.of(neighborBuildRuleInfo);
        }
      }
      currentBuildRuleInfo.longestDependencyChainMillis =
          longestDependencyChainMillis + currentBuildRuleInfo.getDuration();
      currentBuildRuleInfo.previousRuleInLongestDependencyChain = longestDependencyChain;
    }
  }

  /** Implementing {@link TraversableGraph} interface. */
  public static class BuildRuleInfoGraph implements TraversableGraph<BuildRuleInfo> {

    private Map<String, BuildRuleInfo> buildRuleInfos;

    public BuildRuleInfoGraph(Map<String, BuildRuleInfo> buildRuleInfos) {
      this.buildRuleInfos = buildRuleInfos;
    }

    @Override
    public Iterable<BuildRuleInfo> getNodesWithNoIncomingEdges() {
      return getBuildRuleInfos()
          .stream()
          .filter(buildRuleInfo -> buildRuleInfo.dependents.isEmpty())
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<BuildRuleInfo> getNodesWithNoOutgoingEdges() {
      return getBuildRuleInfos()
          .stream()
          .filter(buildRuleInfo -> buildRuleInfo.dependencies.isEmpty())
          .collect(Collectors.toList());
    }

    /** Ignoring nodes, they are not in buildRuleInfos, since not received a start event for them */
    @Override
    public Iterable<BuildRuleInfo> getIncomingNodesFor(BuildRuleInfo sink) {
      return sink.dependents
          .stream()
          .filter(buildRuleInfos::containsKey) // Ignoring Rules without Start event
          .map(buildRuleInfos::get)
          .collect(Collectors.toList());
    }

    /** Ignoring nodes, they are not in buildRuleInfos, since not received a start event for them */
    @Override
    public Iterable<BuildRuleInfo> getOutgoingNodesFor(BuildRuleInfo source) {
      return source
          .dependencies
          .stream()
          .filter(buildRuleInfos::containsKey) // Ignoring Rules without Start event
          .map(buildRuleInfos::get)
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<BuildRuleInfo> getNodes() {
      return getBuildRuleInfos();
    }

    private Collection<BuildRuleInfo> getBuildRuleInfos() {
      return buildRuleInfos.values();
    }
  }

  /**
   * Similar to {@link com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace} keeps
   * information regarding {@link BuildRule}. Start, End times and dependents, dependencies of this
   * {@link BuildRule}.
   */
  public static class BuildRuleInfo {
    public final String ruleName;
    public final long startEpochMillis;
    public long finishEpochMillis;
    public long duration;
    public long longestDependencyChainMillis;
    public Set<String> dependents;
    public Set<String> dependencies;
    public Optional<BuildRuleInfo> previousRuleInLongestDependencyChain;

    public BuildRuleInfo(String ruleName, long startEpochMillis, long finishEpochMillis) {
      this.ruleName = ruleName;
      this.startEpochMillis = startEpochMillis;
      this.finishEpochMillis = finishEpochMillis;
      this.duration = 0;
      this.longestDependencyChainMillis = finishEpochMillis - startEpochMillis;
      this.dependents = Sets.newHashSet();
      this.dependencies = Sets.newHashSet();
      this.previousRuleInLongestDependencyChain = Optional.empty();
    }

    public void addDependent(String dependent) {
      this.dependents.add(dependent);
    }

    public void addDependency(String dependency) {
      this.dependencies.add(dependency);
    }

    public void updateFinish(long finishEpochMillis) {
      this.finishEpochMillis = Math.max(finishEpochMillis, this.finishEpochMillis);
    }

    public void updateDuration(long duration) {
      this.duration += duration;
    }

    public long getDuration() {
      return duration;
    }

    public long getWholeTargetDuration() {
      return this.finishEpochMillis - this.startEpochMillis;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BuildRuleInfo that = (BuildRuleInfo) o;
      return Objects.equal(ruleName, that.ruleName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(ruleName);
    }
  }
}
