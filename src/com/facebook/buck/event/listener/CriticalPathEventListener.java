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
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.logd.client.LogStreamFactory;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.remoteexecution.event.RemoteBuildRuleExecutionEvent;
import com.facebook.buck.support.criticalpath.CriticalPathBuilder;
import com.facebook.buck.support.criticalpath.ReportableCriticalPathNode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
  private final LogStreamFactory logStreamFactory;
  private final CriticalPathBuilder criticalPathBuilder = new CriticalPathBuilder();

  /**
   * Constructor for CriticalPathEventListener
   *
   * @param logStreamFactory log stream factory implementation depending on whether logd is enabled
   * @param outputPath path to critical path log file
   */
  public CriticalPathEventListener(LogStreamFactory logStreamFactory, Path outputPath) {
    this.logStreamFactory = logStreamFactory;
    this.outputPath = Objects.requireNonNull(outputPath);
  }

  /** Subscribes to {@link BuildRuleExecutionEvent.Finished} events */
  @Subscribe
  public void subscribe(BuildRuleExecutionEvent.Finished event) {
    long elapsedTimeMillis = TimeUnit.NANOSECONDS.toMillis(event.getElapsedTimeNano());
    criticalPathBuilder.onBuildRuleCompletedExecution(event.getBuildRule(), elapsedTimeMillis);
  }

  /** Subscribes to {@link FinalizingBuildRuleEvent} events */
  @Subscribe
  public void subscribe(FinalizingBuildRuleEvent event) {
    criticalPathBuilder.onBuildRuleFinalized(event.getBuildRule(), event.getNanoTime());
  }

  /** Subscribes to {@link RemoteBuildRuleExecutionEvent} events */
  @Subscribe
  public void subscribe(RemoteBuildRuleExecutionEvent event) {
    LOG.debug(
        "RemoteBuildRuleExecutionEvent %s took: %s",
        event.getBuildRule().getFullyQualifiedName(), event.getExecutionDurationMs());
    criticalPathBuilder.onBuildRuleCompletedExecution(
        event.getBuildRule(), event.getExecutionDurationMs());
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    LOG.info("Received command finished event for command : %s", event.getCommandName());
    try {
      dumpCriticalPath();
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
    try (BufferedWriter writer =
        new BufferedWriter(
            new OutputStreamWriter(
                logStreamFactory.createLogStream(
                    outputPath.toString(), LogType.CRITICAL_PATH_LOG)))) {
      long criticalPathCost = getCriticalPathExecutionTime();
      for (Pair<BuildTarget, CriticalPathNode> pair : getCriticalPath()) {
        writer.write(convertToLine(pair, criticalPathCost));
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
              return ImmutableCriticalPathReportableNode.ofImpl(
                  pair.getFirst(),
                  criticalPathNode.getSiblingDeltaMs(),
                  criticalPathNode.getExecutionTimeInfo().getExecutionDurationMs(),
                  criticalPathNode.getExecutionTimeInfo().getEventNanoTime(),
                  criticalPathNode.getType());
            })
        .collect(ImmutableList.toImmutableList());
  }

  /** Return the total cost of the critical path, in milliseconds. */
  public long getCriticalPathExecutionTime() {
    ImmutableList<ReportableCriticalPathNode> criticalPath = criticalPathBuilder.getCriticalPath();
    if (criticalPath.isEmpty()) {
      return 0;
    }

    return Iterables.getLast(criticalPath).getPathCostMilliseconds();
  }

  private Collection<Pair<BuildTarget, CriticalPathNode>> getCriticalPath() {
    return criticalPathBuilder.getCriticalPath().stream()
        .map(
            node ->
                new Pair<BuildTarget, CriticalPathNode>(
                    node.getTarget(),
                    ImmutableCriticalPathNode.ofImpl(
                        node.getPathCostMilliseconds(),
                        node.getClosestSiblingExecutionTimeDelta().orElse(0L),
                        node.getType(),
                        ImmutableExecutionTimeInfo.ofImpl(
                            node.getExecutionTimeMilliseconds(), node.getEventNanoTime()))))
        .collect(Collectors.toList());
  }

  private String convertToLine(Pair<BuildTarget, CriticalPathNode> pair, long totalPathCost) {
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
        decimalFormat.format(100. * elapsedTime / totalPathCost),
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

    long getSiblingDeltaMs();

    @Nullable
    String getType();

    ExecutionTimeInfo getExecutionTimeInfo();
  }

  /** */
  @BuckStyleValue
  interface ExecutionTimeInfo {
    long getExecutionDurationMs();

    long getEventNanoTime();
  }
}
