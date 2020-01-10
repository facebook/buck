/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildRuleExecutionEvent;
import com.facebook.buck.core.build.event.FinalizingBuildRuleEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.remoteexecution.event.RemoteBuildRuleExecutionEvent;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * {@link BuckEventListener} that is intended to build Critical path of the build (The longest by
 * time path )
 */
public class CriticalPathEventListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(CriticalPathEventListener.class);

  private static final String FORMAT = "%20s %20s %20s %40s\t\t\t\t%s";
  private final DecimalFormat decimalFormat = new DecimalFormat("#0.00");

  private final Path outputPath;
  @Nullable private BuildTarget longestPathSoFar;
  public long longestTimeSoFar;
  /**
   * Keeping track of longest seen path allows us to find the longest path without iterating over
   * all nodes at the end of the build. In addition, it naturally handles the case of zero-cost
   * nodes at the end of the critical path (they will be included).
   */
  private final Map<BuildTarget, CriticalPathNode> buildTargetToCriticalPathNodeMap =
      new HashMap<>();

  private final Map<BuildTarget, ExecutionTimeInfo> buildTargetToExecutionTimeMap = new HashMap<>();

  public CriticalPathEventListener(Path outputPath) {
    this.outputPath = Objects.requireNonNull(outputPath);
  }

  /** Subscribes to {@link BuildRuleExecutionEvent.Finished} events */
  @Subscribe
  public void subscribe(BuildRuleExecutionEvent.Finished event) {
    BuildTarget buildTarget = event.getTarget();
    long elapsedTimeMillis = TimeUnit.NANOSECONDS.toMillis(event.getElapsedTimeNano());
    buildTargetToExecutionTimeMap.put(
        buildTarget, ImmutableExecutionTimeInfo.of(elapsedTimeMillis, event.getNanoTime()));
  }

  /** Subscribes to {@link FinalizingBuildRuleEvent} events */
  @Subscribe
  public void subscribe(FinalizingBuildRuleEvent event) {
    BuildRule buildRule = event.getBuildRule();
    BuildTarget buildTarget = buildRule.getBuildTarget();
    handleBuildRule(
        buildRule,
        buildTargetToExecutionTimeMap.getOrDefault(
            buildTarget, ImmutableExecutionTimeInfo.of(0L, 0L)));
  }

  /** Subscribes to {@link RemoteBuildRuleExecutionEvent} events */
  @Subscribe
  public void subscribe(RemoteBuildRuleExecutionEvent event) {
    LOG.debug(
        "RemoteBuildRuleExecutionEvent %s took: %s",
        event.getBuildRule().getFullyQualifiedName(), event.getExecutionDurationMs());
    buildTargetToExecutionTimeMap.put(
        event.getBuildRule().getBuildTarget(),
        ImmutableExecutionTimeInfo.of(event.getExecutionDurationMs(), event.getNanoTime()));
  }

  @VisibleForTesting
  void handleBuildRule(BuildRule buildRule, ExecutionTimeInfo executionTimeInfo) {
    Pair<Optional<BuildTarget>, Long> longestPathBeforeGivenRule =
        findTheLongestPathBeforeThisRule(buildRule);
    CriticalPathNode criticalPathNode =
        ImmutableCriticalPathNode.of(
            executionTimeInfo.getExecutionDurationMs() + longestPathBeforeGivenRule.getSecond(),
            buildRule.getType(),
            longestPathBeforeGivenRule.getFirst().orElse(null),
            executionTimeInfo);

    BuildTarget buildTarget = buildRule.getBuildTarget();
    buildTargetToCriticalPathNodeMap.put(buildTarget, criticalPathNode);
    // update longestPathSoFar and longestTimeSoFar if needed
    if (longestPathSoFar == null || longestTimeSoFar < criticalPathNode.getTotalElapsedTimeMs()) {
      longestPathSoFar = buildTarget;
      longestTimeSoFar = criticalPathNode.getTotalElapsedTimeMs();
    }
  }

  private Pair<Optional<BuildTarget>, Long> findTheLongestPathBeforeThisRule(BuildRule buildRule) {
    long longestSoFar = 0;
    BuildTarget resultBuildTarget = null;

    for (BuildRule depBuildRule : buildRule.getBuildDeps()) {
      BuildTarget buildTarget = depBuildRule.getBuildTarget();
      // Load critical path node from tracking map and in case of buildTarget is not found in the
      // map then this mean that this rule was not executed and
      // was downloaded from a cache. In this case we will insert an empty CriticalPathNode into our
      // tracking map
      CriticalPathNode criticalPathNode =
          buildTargetToCriticalPathNodeMap.computeIfAbsent(
              buildTarget,
              ignore ->
                  ImmutableCriticalPathNode.of(
                      0, null, null, ImmutableExecutionTimeInfo.of(0L, 0L)));
      long totalElapsedTime = criticalPathNode.getTotalElapsedTimeMs();
      if (totalElapsedTime > longestSoFar) {
        longestSoFar = totalElapsedTime;
        resultBuildTarget = buildTarget;
      }
    }
    return new Pair<>(Optional.ofNullable(resultBuildTarget), longestSoFar);
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    LOG.info("Received command finished event for command : %s", event.getCommandName());
    try {
      if (longestPathSoFar != null) {
        dumpCriticalPath();
      }
    } catch (IOException e) {
      Path parentDir = outputPath.getParent();
      LOG.warn(
          e,
          "Exception during dumping a critical path result into the path: [%s]."
              + " Extra info: parent directory: [%s], exists: [%s]",
          outputPath,
          parentDir,
          Files.exists(parentDir));
    }
  }

  /** Dumps critical path into the given {@code outputPath} */
  private void dumpCriticalPath() throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      for (Pair<BuildTarget, CriticalPathNode> pair : getCriticalPath()) {
        writer.write(convertToLine(pair));
        writer.newLine();
      }
    }
  }

  /** Return all the critical path nodes for reports */
  public ImmutableList<CriticalPathReportableNode> getCriticalPathReportNodes() {
    return getCriticalPath().stream()
        .map(
            pair -> {
              CriticalPathNode criticalPathNode = pair.getSecond();
              return ImmutableCriticalPathReportableNode.of(
                  pair.getFirst(),
                  criticalPathNode.getExecutionTimeInfo().getExecutionDurationMs(),
                  criticalPathNode.getExecutionTimeInfo().getEventNanoTime(),
                  criticalPathNode.getType());
            })
        .collect(ImmutableList.toImmutableList());
  }

  @VisibleForTesting
  Collection<Pair<BuildTarget, CriticalPathNode>> getCriticalPath() {
    // critical path is reconstructed from the tail pointer. That is why Deque is used here.
    Deque<Pair<BuildTarget, CriticalPathNode>> criticalPathDeque = new ArrayDeque<>();
    BuildTarget current = longestPathSoFar;
    while (current != null) {
      CriticalPathNode criticalPathNode = buildTargetToCriticalPathNodeMap.get(current);
      // tail element inserting to the head of the Deque
      criticalPathDeque.addFirst(new Pair<>(current, criticalPathNode));
      current = criticalPathNode.getPreviousNode();
    }
    return criticalPathDeque;
  }

  private String convertToLine(Pair<BuildTarget, CriticalPathNode> pair) {
    BuildTarget buildTarget = pair.getFirst();
    CriticalPathNode criticalPathNode = pair.getSecond();
    try {
      return String.format(
          "%s: %s",
          buildTarget.getFullyQualifiedName(),
          ObjectMappers.WRITER.writeValueAsString(criticalPathNode));
    } catch (JsonProcessingException e) {
      LOG.info(
          e, "Failed to process critical path node: " + pair.getFirst().getFullyQualifiedName());
    }
    long elapsedTime = criticalPathNode.getExecutionTimeInfo().getExecutionDurationMs();

    return String.format(
        FORMAT,
        elapsedTime,
        criticalPathNode.getTotalElapsedTimeMs(),
        decimalFormat.format(100. * elapsedTime / longestTimeSoFar),
        criticalPathNode.getType(),
        buildTarget.getFullyQualifiedName());
  }

  /**
   * Internal Critical Path node that is used to calculate longest(by time) path in graph (Critical
   * Path)
   */
  @BuckStyleValue
  interface CriticalPathNode {

    long getTotalElapsedTimeMs();

    @Nullable
    String getType();

    @Nullable
    @JsonIgnore
    BuildTarget getPreviousNode();

    ExecutionTimeInfo getExecutionTimeInfo();
  }

  /** */
  @BuckStyleValue
  interface ExecutionTimeInfo {
    long getExecutionDurationMs();

    long getEventNanoTime();
  }
}
