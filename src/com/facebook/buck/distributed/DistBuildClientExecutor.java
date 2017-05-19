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
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DistBuildClientExecutor {

  private static final Logger LOG = Logger.get(DistBuildClientExecutor.class);
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");
  private static final int DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 1000;

  private final DistBuildService distBuildService;
  private final DistBuildLogStateTracker distBuildLogStateTracker;
  private final BuildJobState buildJobState;
  private final BuckVersion buckVersion;
  private final DistBuildClientStatsTracker distBuildClientStats;
  private final ScheduledExecutorService scheduler;
  private final int statusPollIntervalMillis;
  private final Map<RunId, Integer> nextEventIdBySlaveRunId = new HashMap<>();

  public static class ExecutionResult {
    public final StampedeId stampedeId;
    public final int exitCode;

    public ExecutionResult(StampedeId stampedeId, int exitCode) {
      this.stampedeId = stampedeId;
      this.exitCode = exitCode;
    }
  }

  public DistBuildClientExecutor(
      BuildJobState buildJobState,
      DistBuildService distBuildService,
      DistBuildLogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      DistBuildClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler,
      int statusPollIntervalMillis) {
    this.buildJobState = buildJobState;
    this.distBuildService = distBuildService;
    this.distBuildLogStateTracker = distBuildLogStateTracker;
    this.buckVersion = buckVersion;
    this.distBuildClientStats = distBuildClientStats;
    this.scheduler = scheduler;
    this.statusPollIntervalMillis = statusPollIntervalMillis;
  }

  public DistBuildClientExecutor(
      BuildJobState buildJobState,
      DistBuildService distBuildService,
      DistBuildLogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      DistBuildClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler) {
    this(
        buildJobState,
        distBuildService,
        distBuildLogStateTracker,
        buckVersion,
        distBuildClientStats,
        scheduler,
        DEFAULT_STATUS_POLL_INTERVAL_MILLIS);
  }

  private BuildJob initBuild(
      ListeningExecutorService networkExecutorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus,
      BuildMode buildMode,
      int numberOfMinions)
      throws IOException, InterruptedException {

    distBuildClientStats.startCreateBuildTimer();
    BuildJob job = distBuildService.createBuild(buildMode, numberOfMinions);
    distBuildClientStats.stopCreateBuildTimer();

    final StampedeId stampedeId = job.getStampedeId();
    distBuildClientStats.setStampedeId(stampedeId.getId());
    LOG.info("Created job. Build id = " + stampedeId.getId());
    logDebugInfo(job);
    postDistBuildStatusEvent(eventBus, job, ImmutableList.of(), "UPLOADING DATA");

    List<ListenableFuture<?>> asyncJobs = new LinkedList<>();

    LOG.info("Uploading local changes.");
    asyncJobs.add(
        distBuildService.uploadMissingFilesAsync(
            buildJobState.fileHashes, distBuildClientStats, networkExecutorService));

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
    postDistBuildStatusEvent(eventBus, job, ImmutableList.of(), "STARTING REMOTE BUILD");

    distBuildService.setBuckVersion(stampedeId, buckVersion, distBuildClientStats);
    LOG.info("Set Buck Version. Build status: " + job.getStatus().toString());

    distBuildClientStats.startPerformDistributedBuildTimer();
    job = distBuildService.startBuild(stampedeId);
    LOG.info("Started job. Build status: " + job.getStatus().toString());
    logDebugInfo(job);
    return job;
  }

  private void checkTerminateScheduledUpdates(
      BuildJob job, Optional<List<BuildSlaveStatus>> slaveStatuses) {
    if (job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY)
        || job.getStatus().equals(BuildStatus.FAILED)) {
      // Terminate scheduled tasks with a custom exception to indicate success.
      throw new JobCompletedException(job, slaveStatuses);
    }
  }

  private BuildJob fetchBuildInformationFromServer(
      BuildJob job, BuckEventBus eventBus, ListeningExecutorService networkExecutorService) {
    final StampedeId stampedeId = job.getStampedeId();

    try {
      job = distBuildService.getCurrentBuildJobState(stampedeId);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    LOG.info("Got build status: " + job.getStatus().toString());

    if (!job.isSetSlaveInfoByRunId()) {
      postDistBuildStatusEvent(eventBus, job, ImmutableList.of());
      checkTerminateScheduledUpdates(job, Optional.empty());
      return job;
    }

    ListenableFuture<?> slaveEventsFuture =
        fetchAndPostBuildSlaveEventsAsync(job, eventBus, networkExecutorService);
    ListenableFuture<List<BuildSlaveStatus>> slaveStatusesFuture =
        fetchBuildSlaveStatusesAsync(job, networkExecutorService);
    ListenableFuture<?> logStreamingFuture =
        fetchAndProcessRealTimeSlaveLogsAsync(job, networkExecutorService);

    List<BuildSlaveStatus> slaveStatuses = ImmutableList.of();
    try {
      slaveStatuses = slaveStatusesFuture.get();
      postDistBuildStatusEvent(eventBus, job, slaveStatuses);
      slaveEventsFuture.get();
      logStreamingFuture.get();
    } catch (ExecutionException e) {
      LOG.error(e, "Failed to get slave statuses, events or logs.");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    checkTerminateScheduledUpdates(job, Optional.of(slaveStatuses));
    return job;
  }

  public ExecutionResult executeAndPrintFailuresToEventBus(
      ListeningExecutorService networkExecutorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus,
      BuildMode buildMode,
      int numberOfMinions)
      throws IOException, InterruptedException {

    final BuildJob initJob =
        initBuild(
            networkExecutorService,
            projectFilesystem,
            fileHashCache,
            eventBus,
            buildMode,
            numberOfMinions);

    nextEventIdBySlaveRunId.clear();
    ScheduledFuture<?> distBuildStatusUpdatingFuture =
        scheduler.scheduleWithFixedDelay(
            () -> fetchBuildInformationFromServer(initJob, eventBus, networkExecutorService),
            0,
            statusPollIntervalMillis,
            TimeUnit.MILLISECONDS);

    final List<BuildSlaveStatus> buildSlaveStatusList;
    BuildJob finalJob;
    try {
      distBuildStatusUpdatingFuture.get();
      throw new RuntimeException("Unreachable State.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof JobCompletedException) {
        // Everything is awesome.
        finalJob = ((JobCompletedException) e.getCause()).getDistBuildJob();
        buildSlaveStatusList =
            ((JobCompletedException) e.getCause())
                .getBuildSlaveStatuses()
                .orElse(ImmutableList.of());
      } else {
        throw new HumanReadableException(e, "Failed to fetch build information from server.");
      }
    } finally {
      distBuildClientStats.stopPerformDistributedBuildTimer();
    }

    postDistBuildStatusEvent(eventBus, finalJob, buildSlaveStatusList, "FETCHING LOG DIRS");
    materializeSlaveLogDirs(finalJob);

    if (finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY)) {
      LOG.info("DistBuild was successful!");
      postDistBuildStatusEvent(eventBus, finalJob, buildSlaveStatusList, "FINISHED");
    } else {
      LOG.info("DistBuild was not successful!");
      postDistBuildStatusEvent(eventBus, finalJob, buildSlaveStatusList, "FAILED");
    }

    logDebugInfo(finalJob);
    return new ExecutionResult(
        finalJob.getStampedeId(),
        finalJob.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ? 0 : 1);
  }

  private void postDistBuildStatusEvent(
      BuckEventBus eventBus, BuildJob job, List<BuildSlaveStatus> slaveStatuses) {
    postDistBuildStatusEvent(eventBus, job, slaveStatuses, null);
  }

  private void postDistBuildStatusEvent(
      BuckEventBus eventBus,
      BuildJob job,
      List<BuildSlaveStatus> slaveStatuses,
      String statusOverride) {
    Optional<List<LogRecord>> logBook = Optional.empty();

    Optional<String> lastLine = Optional.empty();
    if (job.isSetDebug() && job.getDebug().isSetLogBook()) {
      logBook = Optional.of(job.getDebug().getLogBook());
      if (logBook.get().size() > 0) {
        lastLine = Optional.of(logBook.get().get(logBook.get().size() - 1).getName());
      }
    }

    String stage = statusOverride == null ? job.getStatus().toString() : statusOverride;
    DistBuildStatus status =
        DistBuildStatus.builder()
            .setStatus(stage)
            .setMessage(lastLine)
            .setLogBook(logBook)
            .setSlaveStatuses(slaveStatuses)
            .build();
    eventBus.post(new DistBuildStatusEvent(status));
  }

  private void logDebugInfo(BuildJob job) {
    if (job.isSetDebug() && job.getDebug().getLogBook().size() > 0) {
      LOG.debug("Debug info: ");
      for (LogRecord log : job.getDebug().getLogBook()) {
        LOG.debug(DATE_FORMAT.format(new Date(log.getTimestampMillis())) + log.getName());
      }
    }
  }

  @VisibleForTesting
  ListenableFuture<List<BuildSlaveStatus>> fetchBuildSlaveStatusesAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(ImmutableList.of());
    }

    StampedeId stampedeId = job.getStampedeId();
    List<ListenableFuture<Optional<BuildSlaveStatus>>> slaveStatusFutures = new LinkedList<>();

    // TODO(shivanker, alisdair): Replace this with a multiFetch request.
    for (String id : job.getSlaveInfoByRunId().keySet()) {
      RunId runId = new RunId();
      runId.setId(id);
      slaveStatusFutures.add(
          networkExecutorService.submit(
              () -> distBuildService.fetchBuildSlaveStatus(stampedeId, runId)));
    }

    return Futures.transform(
        Futures.allAsList(slaveStatusFutures),
        slaveStatusList ->
            slaveStatusList
                .stream()
                .filter(Optional::isPresent)
                .map(x -> x.get())
                .collect(Collectors.toList()));
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndPostBuildSlaveEventsAsync(
      BuildJob job, BuckEventBus eventBus, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    StampedeId stampedeId = job.getStampedeId();
    List<BuildSlaveEventsQuery> fetchEventQueries = new LinkedList<>();

    for (String id : job.getSlaveInfoByRunId().keySet()) {
      RunId runId = new RunId();
      runId.setId(id);
      fetchEventQueries.add(
          distBuildService.createBuildSlaveEventsQuery(
              stampedeId, runId, nextEventIdBySlaveRunId.getOrDefault(runId, 0)));
    }
    ListenableFuture<List<Pair<Integer, BuildSlaveEvent>>> fetchEventsFuture =
        networkExecutorService.submit(
            () -> distBuildService.multiGetBuildSlaveEvents(fetchEventQueries));

    ListenableFuture<?> postEventsFuture =
        Futures.transform(
            fetchEventsFuture,
            sequenceIdAndEvents -> {

              // Sort such that all events from the same RunId come together, and in increasing order
              // of their sequence IDs. Also, we cannot directly sort sequenceIdAndEvents as it might
              // be an ImmutableList, hence we make it a stream.
              sequenceIdAndEvents =
                  sequenceIdAndEvents
                      .stream()
                      .sorted(
                          (event1, event2) -> {
                            RunId runId1 = event1.getSecond().getRunId();
                            RunId runId2 = event2.getSecond().getRunId();

                            int result = runId1.compareTo(runId2);
                            if (result == 0) {
                              result = event1.getFirst().compareTo(event2.getFirst());
                            }

                            return result;
                          })
                      .collect(Collectors.toList());

              for (Pair<Integer, BuildSlaveEvent> sequenceIdAndEvent : sequenceIdAndEvents) {
                BuildSlaveEvent slaveEvent = sequenceIdAndEvent.getSecond();
                nextEventIdBySlaveRunId.put(
                    slaveEvent.getRunId(), sequenceIdAndEvent.getFirst() + 1);
                switch (slaveEvent.getEventType()) {
                  case CONSOLE_EVENT:
                    ConsoleEvent consoleEvent =
                        DistBuildUtil.createConsoleEvent(slaveEvent.getConsoleEvent());
                    eventBus.post(consoleEvent);
                    break;
                  case UNKNOWN:
                  default:
                    LOG.error(
                        String.format(
                            "Unknown type of BuildSlaveEvent received: [%d]",
                            slaveEvent.getEventType().getValue()));
                    break;
                }
              }
              return null;
            });

    return postEventsFuture;
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndProcessRealTimeSlaveLogsAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    List<LogLineBatchRequest> newLogLineRequests =
        distBuildLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values());
    if (newLogLineRequests.size() == 0) {
      return Futures.immediateFuture(null);
    }

    return networkExecutorService.submit(
        () -> {
          try {
            MultiGetBuildSlaveRealTimeLogsResponse slaveLogsResponse =
                distBuildService.fetchSlaveLogLines(job.getStampedeId(), newLogLineRequests);
            Preconditions.checkState(slaveLogsResponse.isSetMultiStreamLogs());

            distBuildLogStateTracker.processStreamLogs(slaveLogsResponse.getMultiStreamLogs());
          } catch (IOException e) {
            LOG.error(e, "Encountered error while streaming logs from BuildSlave(s).");
          }
        });
  }

  @VisibleForTesting
  void materializeSlaveLogDirs(BuildJob job) {
    if (!job.isSetSlaveInfoByRunId()) {
      return;
    }

    List<RunId> runIds =
        distBuildLogStateTracker.runIdsToMaterializeLogDirsFor(job.getSlaveInfoByRunId().values());
    if (runIds.size() == 0) {
      return;
    }

    distBuildClientStats.startMaterializeSlaveLogsTimer();

    try {
      MultiGetBuildSlaveLogDirResponse logDirsResponse =
          distBuildService.fetchBuildSlaveLogDir(job.stampedeId, runIds);
      Preconditions.checkState(logDirsResponse.isSetLogDirs());

      distBuildLogStateTracker.materializeLogDirs(logDirsResponse.getLogDirs());
    } catch (IOException ex) {
      LOG.error(ex, "Error fetching slave log directories from frontend.");
    }
    distBuildClientStats.stopMaterializeSlaveLogsTimer();
  }

  public static final class JobCompletedException extends RuntimeException {

    private final BuildJob job;
    private final Optional<List<BuildSlaveStatus>> buildSlaveStatuses;

    private JobCompletedException(
        BuildJob job, Optional<List<BuildSlaveStatus>> buildSlaveStatuses) {
      super(String.format("DistBuild job completed with status: [%s]", job.getStatus().toString()));
      this.job = job;
      this.buildSlaveStatuses = buildSlaveStatuses;
    }

    public BuildJob getDistBuildJob() {
      return job;
    }

    public Optional<List<BuildSlaveStatus>> getBuildSlaveStatuses() {
      return buildSlaveStatuses;
    }
  }
}
