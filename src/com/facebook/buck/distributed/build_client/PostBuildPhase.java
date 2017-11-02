/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_client;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.MATERIALIZE_SLAVE_LOGS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.POST_DISTRIBUTED_BUILD_LOCAL_STEPS;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PUBLISH_BUILD_SLAVE_FINISHED_STATS;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.build_client.BuildSlaveStats.Builder;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Phase after the build. */
public class PostBuildPhase {

  private static final Logger LOG = Logger.get(PostBuildPhase.class);

  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final LogStateTracker distBuildLogStateTracker;
  private final long maxTimeoutWaitingForLogsMillis;
  private final boolean logMaterializationEnabled;

  public PostBuildPhase(
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      LogStateTracker distBuildLogStateTracker,
      long maxTimeoutWaitingForLogsMillis,
      boolean logMaterializationEnabled) {
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.distBuildLogStateTracker = distBuildLogStateTracker;
    this.maxTimeoutWaitingForLogsMillis = maxTimeoutWaitingForLogsMillis;
    this.logMaterializationEnabled = logMaterializationEnabled;
  }

  /** Run all the local steps required after the build. */
  public BuildController.ExecutionResult runPostDistBuildLocalSteps(
      ListeningExecutorService networkExecutorService,
      List<BuildSlaveStatus> buildSlaveStatusList,
      BuildJob finalJob,
      EventSender eventSender)
      throws InterruptedException {
    distBuildClientStats.startTimer(POST_DISTRIBUTED_BUILD_LOCAL_STEPS);

    eventSender.postDistBuildStatusEvent(
        finalJob, buildSlaveStatusList, "FETCHING DIST BUILD STATS");
    distBuildClientStats.startTimer(PUBLISH_BUILD_SLAVE_FINISHED_STATS);
    ListenableFuture<?> slaveFinishedStatsFuture =
        publishBuildSlaveFinishedStatsEvent(finalJob, networkExecutorService, eventSender);
    slaveFinishedStatsFuture =
        Futures.transform(
            slaveFinishedStatsFuture,
            f -> {
              distBuildClientStats.stopTimer(PUBLISH_BUILD_SLAVE_FINISHED_STATS);
              return f;
            });

    eventSender.postDistBuildStatusEvent(finalJob, buildSlaveStatusList, "FETCHING LOG DIRS");

    if (logMaterializationEnabled) {
      materializeSlaveLogDirs(finalJob);
    }

    try {
      slaveFinishedStatsFuture.get();
    } catch (ExecutionException e) {
      LOG.error(e, "Exception while trying to fetch and publish BuildSlaveFinishedStats.");
    }

    if (finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY)) {
      LOG.info("DistBuild was successful!");
      eventSender.postDistBuildStatusEvent(finalJob, buildSlaveStatusList, "FINISHED");
    } else {
      LOG.info("DistBuild was not successful!");
      eventSender.postDistBuildStatusEvent(finalJob, buildSlaveStatusList, "FAILED");
    }

    return new BuildController.ExecutionResult(
        finalJob.getStampedeId(),
        finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ? 0 : 1);
  }

  private void materializeSlaveLogDirs(BuildJob job) {
    if (!job.isSetSlaveInfoByRunId() || job.getSlaveInfoByRunId().isEmpty()) {
      return;
    }

    distBuildClientStats.startTimer(MATERIALIZE_SLAVE_LOGS);
    List<BuildSlaveRunId> logsToFetchAndMaterialize =
        job.getSlaveInfoByRunId()
            .values()
            .stream()
            .map(x -> x.getBuildSlaveRunId())
            .collect(Collectors.toList());
    BuildSlaveLogsMaterializer materializer =
        distBuildLogStateTracker.getBuildSlaveLogsMaterializer();
    if (maxTimeoutWaitingForLogsMillis > 0) {
      try {
        materializer.fetchAndMaterializeAllLogs(
            job.getStampedeId(), logsToFetchAndMaterialize, maxTimeoutWaitingForLogsMillis);
      } catch (TimeoutException e) {
        // Fail the build as we were expecting all logs to be present.
        throw new RuntimeException(e);
      }
    } else {
      materializer.fetchAndMaterializeAvailableLogs(job.getStampedeId(), logsToFetchAndMaterialize);
    }
    distBuildClientStats.stopTimer(MATERIALIZE_SLAVE_LOGS);
  }

  @VisibleForTesting
  ListenableFuture<BuildSlaveStats> publishBuildSlaveFinishedStatsEvent(
      BuildJob job, ListeningExecutorService executor, EventSender eventSender) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    final List<ListenableFuture<Pair<BuildSlaveRunId, Optional<BuildSlaveFinishedStats>>>>
        slaveFinishedStatsFutures = new ArrayList<>(job.getSlaveInfoByRunIdSize());
    for (Map.Entry<String, BuildSlaveInfo> entry : job.getSlaveInfoByRunId().entrySet()) {
      String runIdStr = entry.getKey();
      BuildSlaveRunId runId = entry.getValue().getBuildSlaveRunId();

      Preconditions.checkState(runIdStr.equals(runId.getId().toString()));

      slaveFinishedStatsFutures.add(
          executor.submit(
              () -> {
                Optional<BuildSlaveFinishedStats> stats = fetchStatsForIndividualSlave(job, runId);
                return new Pair<BuildSlaveRunId, Optional<BuildSlaveFinishedStats>>(runId, stats);
              }));
    }

    final Builder builder = BuildSlaveStats.builder().setStampedeId(job.getStampedeId());
    return Futures.transform(
        Futures.allAsList(slaveFinishedStatsFutures),
        statsList -> createAndPublishBuildSlaveStats(builder, statsList, eventSender));
  }

  private BuildSlaveStats createAndPublishBuildSlaveStats(
      Builder builder,
      List<Pair<BuildSlaveRunId, Optional<BuildSlaveFinishedStats>>> statsList,
      EventSender eventSender) {
    for (Pair<BuildSlaveRunId, Optional<BuildSlaveFinishedStats>> entry : statsList) {
      builder.putBuildSlaveStats(entry.getFirst(), entry.getSecond());
    }

    BuildSlaveStats stats = builder.build();
    eventSender.sendBuildFinishedEvent(stats);
    return stats;
  }

  private Optional<BuildSlaveFinishedStats> fetchStatsForIndividualSlave(
      BuildJob job, BuildSlaveRunId runId) {
    Optional<BuildSlaveFinishedStats> finishedStats = Optional.empty();

    try {
      finishedStats = distBuildService.fetchBuildSlaveFinishedStats(job.getStampedeId(), runId);
      if (!finishedStats.isPresent()) {
        LOG.error("BuildSlaveFinishedStats was not set for RunId:[%s] from frontend.", runId);
      }
    } catch (IOException ex) {
      LOG.error(
          ex, "Error fetching BuildSlaveFinishedStats for RunId:[%s] from the frontend.", runId);
    }

    return finishedStats;
  }
}
