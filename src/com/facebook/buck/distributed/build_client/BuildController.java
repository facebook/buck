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

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

/** High level controls the distributed build. */
public class BuildController {
  private static final int DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 1000;

  private final PreBuildPhase preBuildPhase;
  private final BuildPhase buildPhase;
  private final PostBuildPhase postBuildPhase;

  /** Result of a distributed build execution. */
  public static class ExecutionResult {
    public final StampedeId stampedeId;
    public final int exitCode;

    public ExecutionResult(StampedeId stampedeId, int exitCode) {
      this.stampedeId = stampedeId;
      this.exitCode = exitCode;
    }
  }

  public BuildController(
      BuildJobState buildJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      ClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler,
      long maxTimeoutWaitingForLogsMillis,
      int statusPollIntervalMillis,
      boolean logMaterializationEnabled) {
    this.preBuildPhase =
        new PreBuildPhase(
            distBuildService,
            distBuildClientStats,
            buildJobState,
            distBuildCellIndexer,
            buckVersion);
    this.buildPhase =
        new BuildPhase(
            distBuildService,
            distBuildClientStats,
            distBuildLogStateTracker,
            scheduler,
            statusPollIntervalMillis);
    this.postBuildPhase =
        new PostBuildPhase(
            distBuildService,
            distBuildClientStats,
            distBuildLogStateTracker,
            maxTimeoutWaitingForLogsMillis,
            logMaterializationEnabled);
  }

  public BuildController(
      BuildJobState buildJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      ClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler,
      long maxTimeoutWaitingForLogsMillis,
      boolean logMaterializationEnabled) {
    this(
        buildJobState,
        distBuildCellIndexer,
        distBuildService,
        distBuildLogStateTracker,
        buckVersion,
        distBuildClientStats,
        scheduler,
        maxTimeoutWaitingForLogsMillis,
        DEFAULT_STATUS_POLL_INTERVAL_MILLIS,
        logMaterializationEnabled);
  }

  /** Executes the tbuild and prints failures to the event bus. */
  public ExecutionResult executeAndPrintFailuresToEventBus(
      ListeningExecutorService networkExecutorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus,
      BuildMode buildMode,
      int numberOfMinions,
      String repository,
      String tenantId)
      throws IOException, InterruptedException {
    EventSender eventSender = new EventSender(eventBus);
    StampedeId stampedeId =
        preBuildPhase.runPreDistBuildLocalSteps(
            networkExecutorService,
            projectFilesystem,
            fileHashCache,
            eventBus,
            buildMode,
            numberOfMinions,
            repository,
            tenantId);

    BuildPhase.BuildResult buildResult =
        buildPhase.runDistBuildAndUpdateConsoleStatus(
            networkExecutorService, eventSender, stampedeId);

    return postBuildPhase.runPostDistBuildLocalSteps(
        networkExecutorService,
        buildResult.getBuildSlaveStatusList(),
        buildResult.getFinalBuildJob(),
        eventSender);
  }
}
