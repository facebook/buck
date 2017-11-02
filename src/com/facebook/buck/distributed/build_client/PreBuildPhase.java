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

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.CREATE_DISTRIBUTED_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Phase before the build. */
public class PreBuildPhase {
  private static final Logger LOG = Logger.get(PreBuildPhase.class);

  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final BuildJobState buildJobState;
  private final DistBuildCellIndexer distBuildCellIndexer;
  private final BuckVersion buckVersion;

  public PreBuildPhase(
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      BuildJobState buildJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      BuckVersion buckVersion) {
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.buildJobState = buildJobState;
    this.distBuildCellIndexer = distBuildCellIndexer;
    this.buckVersion = buckVersion;
  }

  /** Run all steps required before the build. */
  public StampedeId runPreDistBuildLocalSteps(
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

    distBuildClientStats.startTimer(CREATE_DISTRIBUTED_BUILD);
    BuildJob job = distBuildService.createBuild(buildMode, numberOfMinions, repository, tenantId);
    distBuildClientStats.stopTimer(CREATE_DISTRIBUTED_BUILD);

    final StampedeId stampedeId = job.getStampedeId();
    eventBus.post(new DistBuildCreatedEvent(stampedeId));

    distBuildClientStats.setStampedeId(stampedeId.getId());
    LOG.info("Created job. Build id = " + stampedeId.getId());
    eventSender.postDistBuildStatusEvent(job, ImmutableList.of(), "UPLOADING DATA");

    List<ListenableFuture<?>> asyncJobs = new LinkedList<>();

    LOG.info("Uploading local changes.");
    asyncJobs.add(
        distBuildService.uploadMissingFilesAsync(
            distBuildCellIndexer.getLocalFilesystemsByCellIndex(),
            buildJobState.fileHashes,
            distBuildClientStats,
            networkExecutorService));

    LOG.info("Uploading target graph.");
    asyncJobs.add(
        networkExecutorService.submit(
            () -> {
              try {
                distBuildService.uploadTargetGraph(buildJobState, stampedeId, distBuildClientStats);
              } catch (IOException e) {
                throw new RuntimeException("Failed to upload target graph with exception.", e);
              }
            }));

    LOG.info("Uploading buck dot-files.");
    asyncJobs.add(
        distBuildService.uploadBuckDotFilesAsync(
            stampedeId,
            projectFilesystem,
            fileHashCache,
            distBuildClientStats,
            networkExecutorService));

    try {
      Futures.allAsList(asyncJobs).get();
    } catch (ExecutionException e) {
      LOG.error("Upload failed.");
      throw new RuntimeException(e);
    }
    eventSender.postDistBuildStatusEvent(job, ImmutableList.of(), "STARTING REMOTE BUILD");

    distBuildService.setBuckVersion(stampedeId, buckVersion, distBuildClientStats);
    LOG.info("Set Buck Version. Build status: " + job.getStatus().toString());

    // Everything is now setup remotely to run the distributed build. No more local prep.
    this.distBuildClientStats.stopTimer(LOCAL_PREPARATION);

    return stampedeId;
  }
}
