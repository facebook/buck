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
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.log.Logger;
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

/** Phase after the build. */
public class PostBuildPhase {
  private static final Logger LOG = Logger.get(PostBuildPhase.class);

  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final LogStateTracker distBuildLogStateTracker;

  public PostBuildPhase(
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      LogStateTracker distBuildLogStateTracker) {
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.distBuildLogStateTracker = distBuildLogStateTracker;
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
    materializeSlaveLogDirs(finalJob);
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

    DistBuildUtil.logDebugInfo(finalJob);
    return new BuildController.ExecutionResult(
        finalJob.getStampedeId(),
        finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ? 0 : 1);
  }

  @VisibleForTesting
  void materializeSlaveLogDirs(BuildJob job) {
    if (!job.isSetSlaveInfoByRunId()) {
      return;
    }

    List<BuildSlaveRunId> runIds =
        distBuildLogStateTracker.runIdsToMaterializeLogDirsFor(job.getSlaveInfoByRunId().values());
    if (runIds.size() == 0) {
      return;
    }

    distBuildClientStats.startTimer(MATERIALIZE_SLAVE_LOGS);

    try {
      MultiGetBuildSlaveLogDirResponse logDirsResponse =
          distBuildService.fetchBuildSlaveLogDir(job.stampedeId, runIds);
      Preconditions.checkState(logDirsResponse.isSetLogDirs());

      distBuildLogStateTracker.materializeLogDirs(logDirsResponse.getLogDirs());
    } catch (IOException ex) {
      LOG.error(ex, "Error fetching slave log directories from frontend.");
    } finally {
      distBuildClientStats.stopTimer(MATERIALIZE_SLAVE_LOGS);
    }
  }

  @VisibleForTesting
  ListenableFuture<?> publishBuildSlaveFinishedStatsEvent(
      BuildJob job, ListeningExecutorService executor, EventSender eventSender) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    final List<ListenableFuture<BuildSlaveFinishedStats>> slaveFinishedStatsFutures =
        new ArrayList<>(job.getSlaveInfoByRunIdSize());
    for (Map.Entry<String, BuildSlaveInfo> entry : job.getSlaveInfoByRunId().entrySet()) {
      String runIdStr = entry.getKey();
      BuildSlaveRunId runId = entry.getValue().getBuildSlaveRunId();

      Preconditions.checkState(runIdStr.equals(runId.getId().toString()));

      slaveFinishedStatsFutures.add(
          executor.submit(() -> fetchStatsForIndividualSlave(job, runId)));
    }

    return Futures.transform(
        Futures.allAsList(slaveFinishedStatsFutures),
        statsList -> {
          eventSender.sendBuildFinishedEvent(statsList);
          return null;
        });
  }

  private BuildSlaveFinishedStats fetchStatsForIndividualSlave(
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

    return finishedStats.orElse(
        // This will set the other fields to null for logging later.
        new BuildSlaveFinishedStats()
            .setBuildSlaveStatus(
                new BuildSlaveStatus()
                    .setStampedeId(job.getStampedeId())
                    .setBuildSlaveRunId(runId)));
  }
}
