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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DistBuildClientExecutor {

  private static final Logger LOG = Logger.get(DistBuildClientExecutor.class);
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");
  private static final int MAX_BUILD_DURATION_MILLIS = 10000; // hack. remove.

  private final DistBuildService distBuildService;
  private final BuildJobState buildJobState;
  private final BuckVersion buckVersion;
  private int millisBetweenStatusPoll;

  public DistBuildClientExecutor(
      BuildJobState buildJobState,
      DistBuildService distBuildService,
      int millisBetweenStatusPoll,
      BuckVersion buckVersion) {
    this.buildJobState = buildJobState;
    this.distBuildService = distBuildService;
    this.millisBetweenStatusPoll = millisBetweenStatusPoll;
    this.buckVersion = buckVersion;
  }

  public int executeAndPrintFailuresToEventBus(
      final WeightedListeningExecutorService executorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus)
      throws IOException, InterruptedException {

    BuildJob job = distBuildService.createBuild();
    final BuildId id = job.getBuildId();
    LOG.info("Created job. Build id = " + id.getId());
    logDebugInfo(job);

    List<ListenableFuture<Void>> asyncJobs = new LinkedList<>();
    LOG.info("Uploading local changes.");
    asyncJobs.add(distBuildService.uploadMissingFiles(buildJobState.fileHashes, executorService));

    LOG.info("Uploading target graph.");
    asyncJobs.add(distBuildService.uploadTargetGraph(buildJobState, id, executorService));

    LOG.info("Uploading buck dot-files.");
    asyncJobs.add(distBuildService.uploadBuckDotFiles(
        id,
        projectFilesystem,
        fileHashCache,
        executorService));

    try {
      Futures.allAsList(asyncJobs).get();
    } catch (ExecutionException e) {
      LOG.error("Upload failed.");
      throw new RuntimeException(e);
    }

    distBuildService.setBuckVersion(id, buckVersion);
    LOG.info("Set Buck Version. Build status: " + job.getStatus().toString());

    job = distBuildService.startBuild(id);
    LOG.info("Started job. Build status: " + job.getStatus().toString());
    logDebugInfo(job);

    Stopwatch stopwatch = Stopwatch.createStarted();
    // Keep polling until the build is complete or failed.
    do {
      job = distBuildService.getCurrentBuildJobState(id);
      LOG.info("Got build status: " + job.getStatus().toString());

      DistBuildStatus distBuildStatus =
          prepareStatusFromJob(job)
              .setETAMillis(MAX_BUILD_DURATION_MILLIS - stopwatch.elapsed(TimeUnit.MILLISECONDS))
              .build();
      eventBus.post(new DistBuildStatusEvent(distBuildStatus));

      try {
        // TODO(shivanker): Get rid of sleeps in methods which we want to unit test
        Thread.sleep(millisBetweenStatusPoll);
      } catch (InterruptedException e) {
        LOG.error(e, "BuildStatus polling sleep call has been interrupted unexpectedly.");
      }
    } while (!(job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ||
        job.getStatus().equals(BuildStatus.FAILED)));

    LOG.info("Build was " +
        (job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ? "" : "not ") +
        "successful!");
    logDebugInfo(job);

    DistBuildStatus distBuildStatus = prepareStatusFromJob(job).setETAMillis(0).build();
    eventBus.post(new DistBuildStatusEvent(distBuildStatus));

    return job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ? 0 : 1;
  }

  private DistBuildStatus.Builder prepareStatusFromJob(BuildJob job) {
    Optional<List<LogRecord>> logBook = Optional.empty();

    Optional<Map<String, BuildSlaveInfo>> slaveInfoByRunId =
        job.isSetSlaveInfoByRunId() ? Optional.of(
            job.getSlaveInfoByRunId()) :
            Optional.empty();
    Optional<String> lastLine = Optional.empty();
    if (job.isSetDebug() && job.getDebug().isSetLogBook()) {
      logBook = Optional.of(job.getDebug().getLogBook());
      if (logBook.get().size() > 0) {
        lastLine = Optional.of(logBook.get().get(logBook.get().size() - 1).getName());
      }
    }

    return DistBuildStatus.builder()
        .setStatus(job.getStatus())
        .setMessage(lastLine)
        .setSlaveInfoByRunId(slaveInfoByRunId)
        .setLogBook(logBook);
  }

  private void logDebugInfo(BuildJob job) {
    if (job.isSetDebug() && job.getDebug().getLogBook().size() > 0) {
      LOG.debug("Debug info: ");
      for (LogRecord log : job.getDebug().getLogBook()) {
        LOG.debug(DATE_FORMAT.format(new Date(log.getTimestampMillis())) + log.getName());
      }
    }
  }
}
